package org.skepsun.kototoro.dex

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.reader.DexFileReader
import org.objectweb.asm.MethodVisitor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class DexConversionFailure {
    /** The input is neither a zip (APK / plugin jar) nor a raw dex file. */
    NOT_AN_ARCHIVE,
    /** An archive without any classes*.dex. */
    NO_DEX,
    TOO_LARGE,
    TRANSLATION_FAILED,
}

class DexConversionException(val failure: DexConversionFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/**
 * What a conversion produced. dex2jar replaces a method it cannot translate by one that throws, so a conversion can
 * succeed with some broken bodies: callers should surface [brokenMethods] rather than hide it.
 */
data class DexConversionReport(
    val dexFiles: Int,
    val classes: Int,
    val brokenMethods: List<String>,
    /** Constructor calls R8 had inlined that were pointed at a real constructor again (see [DexRepairPlan]). */
    val repairedInstantiations: Int = 0,
)

/** Converts every `classes*.dex` of an APK or DEX-only plugin jar into one class-file jar. */
object DexJarConverter {
    /** Bump when the library or its settings change: converted artifacts are cached under this version. */
    const val VERSION = 3

    private const val MAXIMUM_DEX_BYTES = 128L * 1024 * 1024
    private const val MAXIMUM_BROKEN_RECORDED = 64
    private val DEX_NAME = Regex("classes(\\d*)\\.dex")

    /**
     * Writes the class files of [source] to [target] (replaced atomically). Non-code resources that the JVM host
     * may need (`META-INF/services`) are carried over, Android resources are not; [extraEntries] adds files such as
     * the text manifest synthesised from an APK.
     */
    fun convert(source: Path, target: Path, extraEntries: Map<String, ByteArray> = emptyMap()): DexConversionReport {
        val dexSources = dexEntries(source)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent ?: Path.of("."), ".convert-", ".tmp")
        try {
            val broken = mutableListOf<String>()
            var classes = 0
            var repaired = 0
            val plan = repairPlan(dexSources)
            ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
                val written = HashSet<String>()
                for (bytes in dexSources.dex()) {
                    val result = translate(bytes, plan, output, written, broken)
                    classes += result.classes
                    repaired += result.repaired
                }
                copyServices(source, output, written)
                for ((name, bytes) in extraEntries) {
                    if (!written.add(name)) continue
                    output.putNextEntry(ZipEntry(name).apply { time = 0 })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            if (classes == 0) throw DexConversionException(DexConversionFailure.NO_DEX)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            return DexConversionReport(dexSources.count, classes, broken, repaired)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private class DexSources(val count: Int, val dex: () -> Sequence<ByteArray>)

    private fun dexEntries(source: Path): DexSources {
        val head = Files.newInputStream(source).use { it.readNBytes(4) }
        if (head.size == 4 && head[0] == 'd'.code.toByte() && head[1] == 'e'.code.toByte() && head[2] == 'x'.code.toByte()) {
            return DexSources(1) { sequenceOf(Files.readAllBytes(source)) }
        }
        val names = try {
            ZipFile(source.toFile()).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && DEX_NAME.matches(it.name) }
                    .sortedBy { DEX_NAME.matchEntire(it.name)!!.groupValues[1].toIntOrNull() ?: 1 }.map { it.name }.toList()
            }
        } catch (error: IOException) {
            throw DexConversionException(DexConversionFailure.NOT_AN_ARCHIVE, error)
        }
        if (names.isEmpty()) throw DexConversionException(DexConversionFailure.NO_DEX)
        // The archive is opened per entry: a suspended generator holding it open would leak the file handle when a
        // translation fails midway (Windows then cannot delete the input).
        return DexSources(names.size) { names.asSequence().map { name -> readDex(source, name) } }
    }

    private fun readDex(source: Path, name: String): ByteArray = ZipFile(source.toFile()).use { zip ->
        val entry = zip.getEntry(name)
        if (entry.size > MAXIMUM_DEX_BYTES) throw DexConversionException(DexConversionFailure.TOO_LARGE)
        zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_DEX_BYTES.toInt() + 1) }.also {
            if (it.size > MAXIMUM_DEX_BYTES) throw DexConversionException(DexConversionFailure.TOO_LARGE)
        }
    }

    /** Classes may live in any dex file, so the repair is planned over all of them before the first is translated. */
    private fun repairPlan(dexSources: DexSources): DexRepairPlan {
        val builder = DexRepairPlan.Builder()
        try {
            for (bytes in dexSources.dex()) builder.add(bytes)
            return builder.build()
        } catch (error: DexConversionException) {
            throw error
        } catch (error: Exception) {
            throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
        }
    }

    private class Translated(val classes: Int, val repaired: Int)

    private fun translate(
        dex: ByteArray, plan: DexRepairPlan, output: ZipOutputStream, written: MutableSet<String>, broken: MutableList<String>,
    ): Translated {
        val handler = object : DexExceptionHandler {
            override fun handleFileException(error: Exception) {
                throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
            }

            override fun handleMethodTranslateException(method: Method, node: DexMethodNode, visitor: MethodVisitor, error: Exception) {
                // dex2jar emits a throwing stub for the method; record it so the caller can see what is degraded.
                if (broken.size < MAXIMUM_BROKEN_RECORDED) broken += "${method.owner}.${method.name}"
            }
        }
        val converted = Files.createTempFile("kototoro-dex-", ".jar")
        try {
            // computeFrames: the JVM verifier insists on stack map frames for class versions that dex2jar emits.
            val reader = DexRepairPlan.Reader(DexFileReader(dex), plan)
            Dex2jar.from(reader).withExceptionHandler(handler).reUseReg(false).topoLogicalSort().skipDebug(true)
                .optimizeSynchronized(false).printIR(false).noCode(false).skipExceptions(false).computeFrames(true).dontSanitizeNames(true).to(converted)
            var count = 0
            ZipFile(converted.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !written.add(entry.name)) continue
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                    if (entry.name.endsWith(".class")) count++
                }
            }
            return Translated(count, reader.repaired)
        } catch (error: DexConversionException) {
            throw error
        } catch (error: Exception) {
            throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
        } finally {
            Files.deleteIfExists(converted)
        }
    }

    /** Service registrations survive conversion of a plugin jar; they are tiny and host-relevant. */
    private fun copyServices(source: Path, output: ZipOutputStream, written: MutableSet<String>) {
        val zip = try { ZipFile(source.toFile()) } catch (_: IOException) { return }
        zip.use {
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith("META-INF/services/") || !written.add(entry.name)) continue
                output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                zip.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
}
