package org.skepsun.kototoro.dex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Opt-in checks on real extension APKs (`-PrealApkDirectory=<dir>` holding `novelfull.apk`, a Tsundoku novel
 * extension, and `animeparadise.apk`, an Aniyomi anime extension). Prints the host classes each one needs.
 */
class RealApkTest {
    @TempDir lateinit var root: Path

    /** Keeps the converted jar for manual inspection (javap) under the module's ignored build directory. */
    private fun keep(converted: Path) {
        val target = Path.of("build/reports/real-apk").also { Files.createDirectories(it) }
        Files.copy(converted, target.resolve(converted.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun apk(name: String): Path {
        val directory = System.getProperty("kototoro.dex.real.apks")
        assumeTrue(directory != null && Files.isRegularFile(Path.of(directory, name)), "No real $name configured")
        return Path.of(directory, name)
    }

    /** Class names a converted jar refers to outside itself, as the constant pools spell them. */
    private fun hostReferences(jar: Path): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        val token = Regex("(?:eu/kanade|uy/kohesive|rx/|okhttp3/|okio/|kotlinx/|org/jsoup|android/|androidx/|keiyoushi/|app/cash|org/json)[A-Za-z0-9_/$.\\-]*")
        ZipFile(jar.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val text = String(zip.getInputStream(entry).readAllBytes(), Charsets.ISO_8859_1)
                for (match in token.findAll(text)) counts.merge(match.value.substringBefore('$'), 1, Int::plus)
            }
        }
        return counts
    }

    /**
     * Loads and links every class of [jar] (JVM verification happens at link time, and `declaredMethods` links).
     * Classes whose host supertypes are not on this test's classpath cannot load; they are counted, not failed.
     */
    private fun verify(jar: Path): List<String> {
        val loader = java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
        val failures = mutableListOf<String>()
        var linked = 0
        var unloadable = 0
        loader.use {
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    val name = entry.name.removeSuffix(".class").replace('/', '.')
                    try {
                        Class.forName(name, false, loader).declaredMethods
                        linked++
                    } catch (error: VerifyError) {
                        failures += "$name: ${error.message?.lineSequence()?.take(3)?.joinToString(" | ")}"
                    } catch (_: LinkageError) {
                        unloadable++
                    } catch (_: ClassNotFoundException) {
                        unloadable++
                    }
                }
            }
        }
        println("  verification: linked=$linked unloadable(host types missing)=$unloadable failures=${failures.size}")
        return failures
    }
    @Test
    fun `a real Tsundoku novel apk converts and its manifest classifies as a novel extension`() {
        val apk = apk("novelfull.apk")
        val manifest = ApkManifestReader.read(apk)
        println("novelfull manifest: ${manifest.packageName} v${manifest.versionCode} (${manifest.versionName}) label=${manifest.label}")
        println("  features=${manifest.features} meta=${manifest.metaData}")
        assertEquals("eu.kanade.tachiyomi.novelextension.en.novelfull", manifest.packageName)
        assertTrue("tachiyomi.novelextension" in manifest.features)
        assertTrue(manifest.metaData.containsKey("tachiyomi.novelextension.class"))
        assertEquals("1.6", manifest.metaData["tachiyomix.extensionLib"])

        val converted = root.resolve("novelfull.jar")
        val report = DexJarConverter.convert(apk, converted)
        println("  converted: ${report.classes} classes, broken=${report.brokenMethods}, repaired=${report.repairedInstantiations}")
        keep(converted)
        assertTrue(report.brokenMethods.isEmpty(), report.brokenMethods.toString())
        assertEquals(emptyList<String>(), verify(converted))
        val entry = manifest.metaData.getValue("tachiyomi.novelextension.class").removePrefix(".")
        ZipFile(converted.toFile()).use { zip ->
            assertTrue(zip.getEntry(manifest.packageName.replace('.', '/') + "/$entry.class") != null ||
                zip.entries().asSequence().any { it.name.endsWith("/$entry.class") }, "entry class $entry")
        }
        hostReferences(converted).forEach { (name, count) -> println("  needs $name ($count)") }
    }

    @Test
    fun `a real Aniyomi anime apk converts and its manifest classifies as an anime extension`() {
        val apk = apk("animeparadise.apk")
        val manifest = ApkManifestReader.read(apk)
        println("animeparadise manifest: ${manifest.packageName} v${manifest.versionCode} (${manifest.versionName}) label=${manifest.label}")
        println("  features=${manifest.features} meta=${manifest.metaData}")
        assertTrue("tachiyomi.animeextension" in manifest.features)
        assertTrue(manifest.metaData.containsKey("tachiyomi.animeextension.class") || manifest.metaData.containsKey("tachiyomi.animeextension.factory"))

        val converted = root.resolve("animeparadise.jar")
        val report = DexJarConverter.convert(apk, converted)
        println("  converted: ${report.classes} classes, broken=${report.brokenMethods}, repaired=${report.repairedInstantiations}")
        keep(converted)
        assertTrue(report.brokenMethods.isEmpty(), report.brokenMethods.toString())
        assertEquals(emptyList<String>(), verify(converted))
        hostReferences(converted).forEach { (name, count) -> println("  needs $name ($count)") }
    }
}
