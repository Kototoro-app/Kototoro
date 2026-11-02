package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceUnavailableException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ParserPluginRegistryTest {
    @TempDir lateinit var root: Path
    private lateinit var platform: TestPlatform
    private lateinit var registry: ParserPluginRegistry

    @BeforeEach
    fun open() {
        platform = TestPlatform(root)
        registry = ParserPluginRegistry(platform)
    }

    @AfterEach
    fun close() {
        registry.close()
        platform.close()
    }

    private fun loadAll() {
        registry.load(Fixtures.tsuki, "uma")
        registry.load(Fixtures.kotatsu, "kotatsu-parsers-redo")
        registry.load(Fixtures.kototoro, "kototoro-parsers")
    }

    private fun sourceNames() = registry.sources().map { it.source.name }.sorted()

    private fun sharedLocale() = registry.sources().single { it.source.name == "FIXTURE_SHARED" }.source.locale

    @Test
    fun `a shared source name is served by the highest priority plugin`() {
        loadAll()
        assertEquals(
            listOf("FIXTURE_KOTATSU_ONLY", "FIXTURE_KOTOTORO_ONLY", "FIXTURE_SHARED", "FIXTURE_TSUKI_ONLY"),
            sourceNames(),
        )
        assertEquals("en", sharedLocale())
        assertTrue(registry.unload("kototoro-parsers"))
        assertEquals("ja", sharedLocale())
        assertTrue(registry.unload("kotatsu-parsers-redo"))
        assertEquals("ko", sharedLocale())
        assertFalse(registry.unload("kototoro-parsers"))
        assertTrue(registry.unload("uma"))
        assertTrue(registry.sources().isEmpty())
    }

    @Test
    fun `source descriptions carry presentation and provenance`() {
        loadAll()
        val novel = registry.sources().single { it.source.name == "FIXTURE_KOTOTORO_ONLY" }
        assertEquals("夹具小说", novel.title)
        assertEquals("NOVEL", novel.source.contentType)
        assertEquals(ParserPluginArchitecture.KOTOTORO, novel.architecture)
        val tsuki = registry.sources().single { it.source.name == "FIXTURE_TSUKI_ONLY" }
        assertEquals("Fixture Tsuki", tsuki.title)
        assertEquals("kototoro-parsers", registry.installed().first { it.metadata.architecture == ParserPluginArchitecture.KOTOTORO }.id)
        val kotatsu = registry.sources().single { it.source.name == "FIXTURE_KOTATSU_ONLY" }
        assertEquals("Fixture Kotatsu", kotatsu.title)
    }

    @Test
    fun `loading the same id twice needs replace and a failed publish keeps the previous plugin`() {
        registry.load(Fixtures.kototoro, "kototoro-parsers")
        assertEquals(
            ParserPluginFailure.ALREADY_LOADED,
            assertThrows<ParserPluginException> { registry.load(Fixtures.kototoro, "kototoro-parsers") }.failure,
        )
        assertThrows<IllegalStateException> {
            registry.replace(Fixtures.kototoro, "kototoro-parsers") { error("persistence failed") }
        }
        assertEquals(2, registry.sources().size)
        var published = 0
        registry.replace(Fixtures.kototoro, "kototoro-parsers") { published++ }
        assertEquals(1, published)
        assertEquals(1, registry.installed().size)
    }

    @Test
    fun `the expected hash is checked before any class is loaded`() {
        assertEquals(
            ParserPluginFailure.HASH_MISMATCH,
            assertThrows<ParserPluginException> { registry.load(Fixtures.tsuki, "uma", "f".repeat(64)) }.failure,
        )
        val hash = ParserPluginInspector().inspect(Fixtures.tsuki).sha256
        registry.load(Fixtures.tsuki, "uma", hash)
        assertEquals(2, registry.sources().size)
        assertThrows<IllegalArgumentException> { registry.load(Fixtures.kotatsu, "../escape") }
    }

    @Test
    fun `an unknown source is unavailable`() {
        loadAll()
        assertThrows<SourceUnavailableException> { runBlocking { registry.withParser("MISSING") {} } }
        assertFalse(registry.owns("MISSING"))
        assertTrue(registry.owns("FIXTURE_SHARED"))
    }

    @Test
    fun `an unloaded plugin keeps its class loader until the running call finishes`() = runBlocking {
        registry.load(Fixtures.kotatsu, "kotatsu-parsers-redo")
        val started = CompletableDeferred<Unit>()
        val unloaded = CompletableDeferred<Unit>()
        val running = async {
            registry.withParser("FIXTURE_KOTATSU_ONLY") { handle ->
                started.complete(Unit)
                unloaded.await()
                // Loads classes lazily from the jar after the plugin has been unloaded.
                handle.parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).size
            }
        }
        started.await()
        assertTrue(registry.unload("kotatsu-parsers-redo"))
        assertFalse(registry.owns("FIXTURE_KOTATSU_ONLY"))
        unloaded.complete(Unit)
        assertEquals(2, running.await())
    }

    @Test
    fun `a catalogued plugin registers its sources without loading and loads on its first call`() = runBlocking {
        val catalogs = root.resolve("catalogs")
        val artifact = Files.copy(Fixtures.kotatsu, root.resolve("plugin.jar"))
        val hash = ParserPluginInspector().inspect(artifact).sha256
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { it.load(artifact, "kotatsu-parsers-redo", hash) }
        assertTrue(Files.isRegularFile(catalogs.resolve("$hash.catalog")))

        // Unreadable as a jar: only the catalog can describe it, and the first call must notice.
        Files.copy(Fixtures.png.inputStream(), artifact, StandardCopyOption.REPLACE_EXISTING)
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { cached ->
            val info = cached.load(artifact, "kotatsu-parsers-redo", hash)
            assertEquals(ParserPluginArchitecture.KOTATSU, info.metadata.architecture)
            assertEquals(hash, info.metadata.sha256)
            val listed = cached.sources().single { it.source.name == "FIXTURE_KOTATSU_ONLY" }
            assertEquals("Fixture Kotatsu", listed.title)
            assertEquals("kotatsu-parsers-redo", listed.pluginId)
            assertTrue(cached.owns("FIXTURE_SHARED"))
            assertThrows<ParserPluginException> { runBlocking { cached.withParser("FIXTURE_KOTATSU_ONLY") {} } }
        }

        // The original artifact back: the catalogued plugin loads lazily and serves calls.
        Files.copy(Fixtures.kotatsu, artifact, StandardCopyOption.REPLACE_EXISTING)
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { cached ->
            cached.load(artifact, "kotatsu-parsers-redo", hash)
            val size = cached.withParser("FIXTURE_KOTATSU_ONLY") { handle ->
                handle.parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).size
            }
            assertEquals(2, size)
        }
    }

    @Test
    fun `a damaged catalog falls back to a full load and is rewritten`() {
        val catalogs = Files.createDirectories(root.resolve("catalogs"))
        val hash = ParserPluginInspector().inspect(Fixtures.tsuki).sha256
        Files.writeString(catalogs.resolve("$hash.catalog"), "not a catalog")
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { it.load(Fixtures.tsuki, "uma", hash) }
        assertTrue(Files.readAllLines(catalogs.resolve("$hash.catalog")).first().startsWith("kototoro-parser-catalog\t"))
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { cached ->
            cached.load(Fixtures.tsuki, "uma", hash)
            assertEquals(2, cached.sources().size)
        }
    }

    @Test
    fun `a parser the plugin marks broken is flagged, kept callable and remembered by the catalog`() = runBlocking {
        val catalogs = root.resolve("catalogs")
        val artifact = Files.copy(Fixtures.broken, root.resolve("broken-plugin.jar"))
        val hash = ParserPluginInspector().inspect(artifact).sha256
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { broken ->
            val info = broken.load(artifact, "kototoro-parsers", hash)
            val source = info.sources.single { it.source.name == "FIXTURE_BROKEN" }
            assertTrue(source.broken, "the @Broken annotation was not read")
            assertEquals("Fixture Broken", source.title)
            // Android keeps a broken parser installed and usable; only the app's source lists hide it.
            val size = broken.withParser("FIXTURE_BROKEN") { handle ->
                handle.parser.getList(0, SortOrder.POPULARITY, ContentListFilter.EMPTY).size
            }
            assertEquals(0, size)
        }
        // The catalog carries the flag too: replace the artifact with a non-jar and register from the catalog alone.
        Files.copy(Fixtures.png.inputStream(), artifact, StandardCopyOption.REPLACE_EXISTING)
        ParserPluginRegistry(platform, catalogDirectory = catalogs).use { cached ->
            cached.load(artifact, "kototoro-parsers", hash)
            assertTrue(cached.sources().single { it.source.name == "FIXTURE_BROKEN" }.broken)
        }
    }

    @Test
    fun `a closed registry refuses work`() {
        loadAll()
        registry.close()
        assertEquals(ParserPluginFailure.CLOSED, assertThrows<ParserPluginException> { registry.sources() }.failure)
        registry.close()
    }
}
