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
import java.nio.file.Path

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
    fun `a closed registry refuses work`() {
        loadAll()
        registry.close()
        assertEquals(ParserPluginFailure.CLOSED, assertThrows<ParserPluginException> { registry.sources() }.failure)
        registry.close()
    }
}
