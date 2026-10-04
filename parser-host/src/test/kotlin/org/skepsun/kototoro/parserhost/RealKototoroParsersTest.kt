package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Smoke test against a real, unmodified kototoro-parsers JVM jar (its Gradle `jar` output, i.e. what the Android
 * release workflow dexes). Skipped unless the jar exists; supply it with `-PkototoroParsersJar=<path>` or keep the
 * sibling checkout built at `../kototoro-parsers/build/libs`. It never touches the network: every request fails.
 */
class RealKototoroParsersTest {
    @TempDir lateinit var root: Path

    private fun jar(): Path? {
        val configured = System.getProperty("kototoro.parserhost.real.kototoro")?.let(Path::of)
        val sibling = Path.of("..", "..", "kototoro-parsers", "build", "libs", "kototoro-parsers-1.0.jar").toAbsolutePath().normalize()
        return listOfNotNull(configured, sibling, Path.of("..", "kototoro-parsers", "build", "libs", "kototoro-parsers-1.0.jar").toAbsolutePath().normalize())
            .firstOrNull(Files::isRegularFile)
    }

    @Test
    fun `the real plugin loads and every declared source constructs offline`() {
        val jar = jar()
        assumeTrue(jar != null, "No raw kototoro-parsers jar available")
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline test") }.build()
        TestPlatform(root, offline).use { platform ->
            ParserPluginRegistry(platform).use { registry ->
                val started = System.nanoTime()
                val plugin = registry.load(jar!!, "kototoro-parsers")
                val loadMillis = (System.nanoTime() - started) / 1_000_000
                assertEquals(ParserPluginArchitecture.KOTOTORO, plugin.metadata.architecture)
                val names = plugin.sources.map { it.source.name }
                assertTrue(names.size > 50, "Declared sources: ${names.size}")
                assertEquals(names.size, names.toSet().size)

                val runtime = ParserSourceRuntime(registry, platform)
                val failures = mutableListOf<String>()
                val types = sortedMapOf<String, Int>()
                runBlocking {
                    for (info in plugin.sources) {
                        try {
                            val descriptor = runtime.describe(info.source.name)
                            assertTrue(descriptor.sortOrders.isNotEmpty())
                            types.merge(descriptor.source.contentType, 1, Int::plus)
                        } catch (error: Throwable) {
                            failures += "${info.source.name}: ${error.javaClass.simpleName}: ${error.message?.take(120)}"
                        }
                    }
                }
                println("kototoro-parsers: ${names.size} sources, load ${loadMillis} ms, constructed ${names.size - failures.size}, types=$types")
                failures.forEach { println("  construct failure: $it") }
                // A few sources may need a browser or network at construction time; the vast majority must not.
                assertTrue(failures.size * 20 <= names.size, "Too many construction failures: ${failures.size}/${names.size}")
            }
        }
    }
}
