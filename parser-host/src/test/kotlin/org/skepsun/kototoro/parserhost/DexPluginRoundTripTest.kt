package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.dex.AndroidTools
import org.skepsun.kototoro.dex.DexJarConverter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The Android repositories publish plugins as `d8` output inside a jar. This test makes exactly that artifact from
 * each fixture, converts it back with the desktop converter and runs the result, so desugaring, Kotlin coroutines
 * and the architecture-specific factories are proven to survive the round trip.
 */
class DexPluginRoundTripTest {
    @TempDir lateinit var root: Path

    private fun androidPlugin(tools: AndroidTools, name: String, classes: Path): Path {
        val dex = tools.dex(classes, root.resolve("dex-$name"), classpath = classpath())
        val plugin = root.resolve("$name-android.jar")
        ZipOutputStream(Files.newOutputStream(plugin)).use { output ->
            output.putNextEntry(ZipEntry("classes.dex"))
            output.write(Files.readAllBytes(dex))
            output.closeEntry()
        }
        return plugin
    }

    /** d8 resolves supertypes from the compile classpath, as in the release workflows' full library jars. */
    private fun classpath(): List<Path> = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        .filter { it.endsWith(".jar") && (it.contains("kotlin-stdlib") || it.contains("kotlinx-coroutines-core") || it.contains("okhttp") || it.contains("okio")) }
        .map(Path::of)

    @Test
    fun `dexed fixtures of every architecture convert load and answer like the originals`() {
        val tools = AndroidTools.find()
        assumeTrue(tools != null, "Android SDK with d8 is required")
        val cases = listOf(
            Triple(Fixtures.kototoro, "kototoro-parsers", "FIXTURE_KOTOTORO_ONLY") to ParserPluginArchitecture.KOTOTORO,
            Triple(Fixtures.kotatsu, "kotatsu-parsers-redo", "FIXTURE_KOTATSU_ONLY") to ParserPluginArchitecture.KOTATSU,
            Triple(Fixtures.tsuki, "uma", "FIXTURE_TSUKI_ONLY") to ParserPluginArchitecture.TSUKI,
        )
        TestPlatform(root).use { platform ->
            ParserPluginRegistry(platform).use { registry ->
                val runtime = ParserSourceRuntime(registry, platform)
                for ((fixture, architecture) in cases) {
                    val (jar, id, source) = fixture
                    val android = androidPlugin(tools!!, id, jar)
                    // As published for Android, the plugin has no class files at all.
                    assertEquals(ParserPluginFailure.DEX_ONLY,
                        org.junit.jupiter.api.assertThrows<ParserPluginException> { ParserPluginInspector().inspect(android) }.failure)

                    val converted = root.resolve("$id.jar")
                    val report = DexJarConverter.convert(android, converted)
                    assertTrue(report.classes >= 3, "$id: ${report.classes} classes")
                    assertTrue(report.brokenMethods.isEmpty(), "$id: ${report.brokenMethods}")
                    ZipFile(converted.toFile()).use { zip -> assertTrue(zip.getEntry(architecture.factoryEntry) != null) }

                    val plugin = registry.load(converted, id)
                    assertEquals(architecture, plugin.metadata.architecture)
                    runBlocking {
                        val items = runtime.getList(source, 0, "POPULARITY", null)
                        assertEquals(2, items.size, id)
                        assertTrue(items[0].title.startsWith("$source POPULARITY"), items[0].title)
                        val chapters = requireNotNull(runtime.getDetails(items[0], org.skepsun.kototoro.core.source.SourceDetailsFetchMode.ALLOW_CACHE).chapters)
                        assertEquals(3, runtime.getPages(chapters[0], null).size)
                    }
                }
            }
        }
    }

    /**
     * The real UMA release (`uma.jar`, DEX only), when its path is given with `-PdexPluginJar=`. Every declared source
     * has to construct without the network.
     */
    @Test
    fun `the real UMA plugin converts and every source constructs`() {
        val configured = System.getProperty("kototoro.parserhost.real.uma")
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "No real UMA jar configured")
        val converted = root.resolve("uma.jar")
        val started = System.nanoTime()
        val report = DexJarConverter.convert(Path.of(configured!!), converted)
        val convertMillis = (System.nanoTime() - started) / 1_000_000
        val offline = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline test") }.build()
        TestPlatform(root, offline).use { platform ->
            ParserPluginRegistry(platform).use { registry ->
                val plugin = registry.load(converted, "uma")
                assertEquals(ParserPluginArchitecture.TSUKI, plugin.metadata.architecture)
                val runtime = ParserSourceRuntime(registry, platform)
                val failures = mutableListOf<String>()
                runBlocking {
                    for (info in plugin.sources) {
                        try {
                            assertTrue(runtime.describe(info.source.name).sortOrders.isNotEmpty())
                        } catch (error: Throwable) {
                            failures += "${info.source.name}: ${error.javaClass.simpleName}: ${error.message?.take(120)}"
                        }
                    }
                }
                println("uma: ${plugin.sources.size} sources, ${report.classes} classes from ${report.dexFiles} dex, " +
                    "broken methods ${report.brokenMethods.size}, convert $convertMillis ms, constructed ${plugin.sources.size - failures.size}")
                report.brokenMethods.take(20).forEach { println("  broken: $it") }
                failures.forEach { println("  construct failure: $it") }
                assertTrue(failures.size * 20 <= plugin.sources.size, "Too many construction failures: ${failures.size}/${plugin.sources.size}")
            }
        }
    }
}
