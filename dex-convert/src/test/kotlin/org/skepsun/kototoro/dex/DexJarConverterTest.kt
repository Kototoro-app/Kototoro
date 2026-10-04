package org.skepsun.kototoro.dex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DexJarConverterTest {
    @TempDir lateinit var root: Path

    private fun zip(path: Path, entries: Map<String, ByteArray>): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private val greeter = mapOf(
        "fixture/Greeter.java" to """
            package fixture;
            import java.util.*;
            public class Greeter {
                public interface Listener { String on(String value); }
                public static String greet(String name) {
                    List<String> parts = new ArrayList<>(Arrays.asList("hello", name));
                    Listener listener = value -> value.toUpperCase(Locale.ROOT);
                    StringBuilder out = new StringBuilder();
                    for (String part : parts) out.append(listener.on(part)).append(' ');
                    synchronized (out) { return out.toString().trim(); }
                }
            }
        """.trimIndent(),
    )

    @Test
    fun `a dex only plugin jar becomes class files that run`() {
        val tools = AndroidTools.find()
        assumeTrue(tools != null, "Android SDK with d8 is required")
        val dex = tools!!.dex(tools.compileJar(root.resolve("compile"), greeter), root.resolve("dex"))
        val plugin = zip(root.resolve("plugin.jar"), mapOf("classes.dex" to Files.readAllBytes(dex), "fixture/" to ByteArray(0)))

        val target = root.resolve("converted.jar")
        val report = DexJarConverter.convert(plugin, target)
        assertEquals(1, report.dexFiles)
        assertTrue(report.classes >= 1)
        assertTrue(report.brokenMethods.isEmpty(), report.brokenMethods.toString())
        ZipFile(target.toFile()).use { zip ->
            assertTrue(zip.entries().asSequence().none { it.name.endsWith(".dex") })
            assertTrue(zip.getEntry("fixture/Greeter.class") != null)
        }
        URLClassLoader(arrayOf(target.toUri().toURL()), null).use { loader ->
            val greet = loader.loadClass("fixture.Greeter").getMethod("greet", String::class.java)
            // Desugared lambdas and synchronized blocks survive the d8 -> dex2jar round trip.
            assertEquals("HELLO KT", greet.invoke(null, "kt"))
        }
    }

    @Test
    fun `every classes dex of an apk is converted and services are carried over`() {
        val tools = AndroidTools.find()
        assumeTrue(tools != null, "Android SDK with d8 is required")
        val first = tools!!.dex(tools.compileJar(root.resolve("a"), greeter), root.resolve("dex-a"))
        val second = tools.dex(tools.compileJar(root.resolve("b"), mapOf(
            "other/Other.java" to "package other; public class Other { public static int answer() { return 42; } }",
        )), root.resolve("dex-b"))
        val apk = zip(root.resolve("extension.apk"), mapOf(
            "classes2.dex" to Files.readAllBytes(second),
            "classes.dex" to Files.readAllBytes(first),
            "META-INF/services/fixture.Service" to "other.Other".toByteArray(),
            "res/layout/unused.xml" to ByteArray(4),
        ))
        val target = root.resolve("converted.jar")
        val report = DexJarConverter.convert(apk, target)
        assertEquals(2, report.dexFiles)
        ZipFile(target.toFile()).use { zip ->
            assertTrue(zip.getEntry("fixture/Greeter.class") != null && zip.getEntry("other/Other.class") != null)
            assertTrue(zip.getEntry("META-INF/services/fixture.Service") != null)
            assertTrue(zip.getEntry("res/layout/unused.xml") == null, "Android resources are not carried over")
        }
    }

    @Test
    fun `a raw dex file converts too`() {
        val tools = AndroidTools.find()
        assumeTrue(tools != null, "Android SDK with d8 is required")
        val dex = tools!!.dex(tools.compileJar(root.resolve("compile"), greeter), root.resolve("dex"))
        val report = DexJarConverter.convert(dex, root.resolve("raw.jar"))
        assertEquals(1, report.dexFiles)
        assertTrue(Files.isRegularFile(root.resolve("raw.jar")))
    }

    @Test
    fun `inputs that are not convertible fail without leaving output`() {
        val garbage = Files.write(root.resolve("garbage.jar"), ByteArray(64) { 9 })
        assertEquals(DexConversionFailure.NOT_AN_ARCHIVE,
            assertThrows<DexConversionException> { DexJarConverter.convert(garbage, root.resolve("out1.jar")) }.failure)
        val noDex = zip(root.resolve("nodex.jar"), mapOf("a.txt" to ByteArray(1)))
        assertEquals(DexConversionFailure.NO_DEX,
            assertThrows<DexConversionException> { DexJarConverter.convert(noDex, root.resolve("out2.jar")) }.failure)
        val corrupt = zip(root.resolve("corrupt.jar"), mapOf("classes.dex" to ByteArray(40) { 3 }))
        assertEquals(DexConversionFailure.TRANSLATION_FAILED,
            assertThrows<DexConversionException> { DexJarConverter.convert(corrupt, root.resolve("out3.jar")) }.failure)
        Files.list(root).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().startsWith("out") || it.fileName.toString().startsWith(".convert-") })
        }
    }
}
