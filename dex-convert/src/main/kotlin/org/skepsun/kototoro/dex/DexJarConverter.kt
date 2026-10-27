package org.skepsun.kototoro.dex

import com.googlecode.d2j.Method
import com.googlecode.d2j.converter.IR2JConverter
import com.googlecode.d2j.dex.ClassVisitorFactory
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.dex.ExDex2Asm
import com.googlecode.d2j.dex.LambadaNameSafeClassAdapter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.dex2jar.ir.IrMethod
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Random
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    const val VERSION = 7

    private const val MAXIMUM_DEX_BYTES = 128L * 1024 * 1024
    private const val MAXIMUM_BROKEN_RECORDED = 64
    private val DEX_NAME = Regex("classes(\\d*)\\.dex")

    /**
     * Writes the class files of [source] to [target] (replaced atomically). Non-code resources that the JVM host
     * may need (`META-INF/services`, and the APK's `assets/`) are carried over, compiled Android resources are not;
     * [extraEntries] adds files such as the text manifest synthesised from an APK.
     */
    fun convert(source: Path, target: Path, extraEntries: Map<String, ByteArray> = emptyMap()): DexConversionReport {
        val dexSources = dexEntries(source)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent ?: Path.of("."), ".convert-", ".tmp")
        try {
            val broken = mutableListOf<String>()
            var classes = 0
            var repaired = 0
            val planned = repairPlan(dexSources)
            ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
                val written = HashSet<String>()
                val nodes = planned.parsed?.let(::sequenceOf) ?: dexSources.dex().map(::parse)
                for (node in nodes) {
                    val result = translate(node, planned.plan, output, written, broken)
                    classes += result.classes
                    repaired += result.repaired
                }
                copyResources(source, output, written)
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

    /** [parsed] keeps a single dex file's classes for translation; several are parsed again one at a time. */
    private class Planned(val plan: DexRepairPlan, val parsed: DexFileNode?)

    /**
     * Classes may live in any dex file, so the repair is planned over all of them before the first is translated.
     * Parsing is a third of an interpreter-only conversion (strings are decoded on every reference), so the usual
     * single-dex input is parsed once; multidex APKs are not all held in memory at the same time.
     */
    private fun repairPlan(dexSources: DexSources): Planned {
        val builder = DexRepairPlan.Builder()
        var parsed: DexFileNode? = null
        for (bytes in dexSources.dex()) {
            val node = parse(bytes)
            builder.add(node)
            if (dexSources.count == 1) parsed = node
        }
        return Planned(builder.build(), parsed)
    }

    /** The reader settings dex2jar used for this converter; the frame/name flags do not affect parsing. */
    private fun parse(dex: ByteArray): DexFileNode = try {
        DexFileNode().also { DexFileReader(dex).accept(it, DexFileReader.SKIP_DEBUG or DexFileReader.IGNORE_READ_EXCEPTION) }
    } catch (error: Exception) {
        throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
    }

    private class Translated(val classes: Int, val repaired: Int)

    private fun translate(
        node: DexFileNode, plan: DexRepairPlan, output: ZipOutputStream, written: MutableSet<String>, broken: MutableList<String>,
    ): Translated {
        val handler = object : DexExceptionHandler {
            override fun handleFileException(error: Exception) {
                throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
            }

            override fun handleMethodTranslateException(method: Method, node: DexMethodNode, visitor: MethodVisitor, error: Exception) {
                // dex2jar emits a throwing stub for the method; record it so the caller can see what is degraded.
                synchronized(broken) { if (broken.size < MAXIMUM_BROKEN_RECORDED) broken += "${method.owner}.${method.name}" }
            }
        }
        try {
            val repaired = plan.apply(node)
            // Entries keep dex2jar's directory-walk order (sorted paths).
            val classes = TreeMap<String, ByteArray>()
            translateClasses(node, handler) { name, bytes -> synchronized(classes) { classes["$name.class"] = bytes } }
            var count = 0
            for ((name, bytes) in classes) {
                if (!written.add(name)) continue
                output.putNextEntry(ZipEntry(name).apply { time = 0 })
                output.write(bytes)
                output.closeEntry()
                count++
            }
            return Translated(count, repaired)
        } catch (error: DexConversionException) {
            throw error
        } catch (error: Exception) {
            throw DexConversionException(DexConversionFailure.TRANSLATION_FAILED, error)
        }
    }

    /**
     * `Dex2jar.doTranslate` with the settings this converter always used (skip debug info, compute frames, keep
     * names, no register reuse or synchronized optimisation), except that [LinearRemoveLocalFromSSA] replaces the
     * cubic stock pass, the already parsed and repaired classes are translated, and class files are handed over in
     * memory: Mobile OpenJDK omits jdk.zipfs, and the former temporary directory only cost interpreter time on iOS.
     */
    private fun translateClasses(fileNode: DexFileNode, handler: DexExceptionHandler, sink: (String, ByteArray) -> Unit) {
        // The helper method prefix for long hex strings comes from this shared generator: reset it so a conversion
        // does not depend on what the process translated before.
        Dex2jar.random = Random(0)
        val parentsByName = HashMap<String, String>()
        for (clz in fileNode.clzs) {
            val parent = clz.superClass ?: continue
            parentsByName[internalName(clz.className)] = internalName(parent)
        }
        val factory = ClassVisitorFactory {
            // computeFrames: the JVM verifier insists on stack map frames for class versions that dex2jar emits.
            val writer = FrameClassWriter(parentsByName)
            val adapter = LambadaNameSafeClassAdapter(writer, true)
            object : ClassVisitor(Opcodes.ASM9, adapter) {
                override fun visitEnd() {
                    super.visitEnd()
                    val bytes = try {
                        writer.toByteArray()
                    } catch (error: Exception) {
                        handler.handleFileException(error)
                        return
                    }
                    sink(adapter.className, bytes)
                }
            }
        }
        val clzs = fileNode.clzs ?: return
        val info = Translator.classInfo(fileNode)
        // Classes translate independently (dex2jar's transformers keep no state), and the iOS JVM only interprets:
        // a few threads cut the wait for large plugins roughly by the number of cores used.
        val workers = if (clzs.size < PARALLEL_MINIMUM_CLASSES) 1 else Runtime.getRuntime().availableProcessors().coerceIn(1, MAXIMUM_WORKERS)
        val next = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        fun work() {
            val translator = Translator(handler)
            while (failure.get() == null) {
                val index = next.getAndIncrement()
                if (index >= clzs.size) return
                try {
                    translator.convertClass(fileNode, clzs[index], factory, info)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
        if (workers == 1) work() else {
            val threads = List(workers - 1) { Thread(::work, "dex-convert-${it + 1}").apply { start() } }
            work()
            threads.forEach(Thread::join)
        }
        failure.get()?.let { throw it }
    }

    private const val PARALLEL_MINIMUM_CLASSES = 64
    private const val MAXIMUM_WORKERS = 4

    private class Translator(handler: DexExceptionHandler) : ExDex2Asm(handler) {
        override fun optimize(irMethod: IrMethod) {
            T_CLEAN_LABEL.transform(irMethod)
            T_DEAD_CODE.transform(irMethod)
            REMOVE_LOCAL.transform(irMethod)
            T_REMOVE_CONST.transform(irMethod)
            T_ZERO.transform(irMethod)
            if (T_NPE.transformReportChanged(irMethod)) {
                T_DEAD_CODE.transform(irMethod)
                REMOVE_LOCAL.transform(irMethod)
                T_REMOVE_CONST.transform(irMethod)
            }
            T_NEW.transform(irMethod)
            T_FILL_ARRAY.transform(irMethod)
            T_AGG.transform(irMethod)
            T_MULTI_ARRAY.transform(irMethod)
            T_VOID_INVOKE.transform(irMethod)
            // https://github.com/pxb1988/dex2jar/issues/477: dead code found in unssa, clean up.
            T_DEAD_CODE.transform(irMethod)
            REMOVE_LOCAL.transform(irMethod)
            T_REMOVE_CONST.transform(irMethod)
            T_TYPE.transform(irMethod)
            T_UNSSA.transform(irMethod)
            T_IR_2_J_REG_ASSIGN.transform(irMethod)
            T_TRIM_EX.transform(irMethod)
        }

        override fun ir2j(irMethod: IrMethod, mv: MethodVisitor, clzCtx: ClzCtx) {
            IR2JConverter().optimizeSynchronized(false).clzCtx(clzCtx).ir(irMethod).asm(mv).convert()
        }

        companion object {
            /** Inner/enclosing class relations across the whole dex file, shared read-only by the workers. */
            fun classInfo(node: DexFileNode): Map<String, Clz> = collectClzInfo(node)
        }
    }

    private val REMOVE_LOCAL = LinearRemoveLocalFromSSA()

    private fun internalName(descriptor: String): String =
        if (descriptor.endsWith(";")) descriptor.substring(1, descriptor.length - 1) else descriptor

    /** dex2jar's frame writer: resolves common parents among the translated classes before asking the host. */
    private class FrameClassWriter(private val parentsByName: Map<String, String>) : ClassWriter(COMPUTE_FRAMES) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            if (type1 == type2) return type1
            var first = type1
            val parentsOfFirst = hashSetOf(first)
            while (true) {
                first = parentsByName[first] ?: break
                parentsOfFirst += first
            }
            var second = type2
            while (true) {
                second = parentsByName[second] ?: break
                if (second in parentsOfFirst) return second
            }
            return try {
                super.getCommonSuperClass(first, second)
            } catch (_: Throwable) {
                // dex2jar 2.4.38 falls back to the misspelt java/util/Object here, which no JVM can load.
                "java/lang/Object"
            }
        }
    }

    /**
     * Service registrations survive conversion of a plugin jar, and so do an APK's `assets/`: Android's class loader
     * serves every entry of the package, and extensions read their bundled scripts that way
     * (`javaClass.getResource("/assets/chapter_list.js")` in Comix, which runs them in a WebView).
     */
    private fun copyResources(source: Path, output: ZipOutputStream, written: MutableSet<String>) {
        val zip = try { ZipFile(source.toFile()) } catch (_: IOException) { return }
        zip.use {
            for (entry in zip.entries()) {
                val carried = entry.name.startsWith("META-INF/services/") || entry.name.startsWith("assets/")
                if (entry.isDirectory || !carried || !written.add(entry.name)) continue
                output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                zip.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
}
