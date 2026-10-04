package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Path

class AniyomiSourceRuntimeTest {
    @TempDir lateinit var directory: Path

    private fun run(block: suspend (MihonJarRegistry, AniyomiSourceRuntime, SourceProtocolClient) -> Unit) = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val path = fixture.animeJar()
        fixture.jars.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                registry.load(path, fixture.jars.identity(path))
                val runtime = AniyomiSourceRuntime(registry)
                var sequence = 0
                block(registry, runtime, SourceProtocolClient(SourceEndpoint(runtime)) { "a${sequence++}" })
            }
        }
    }

    @Test
    fun `an anime extension registers as ANIYOMI video sources and only its own runtime owns them`() = run { registry, runtime, client ->
        val source = client.getSources().single()
        assertEquals("ANIYOMI_777", source.name)
        assertEquals("VIDEO", source.contentType)
        assertEquals("ja", source.locale)
        assertEquals(SourceEcosystem.ANIYOMI, registry.installed().single().metadata.ecosystem)
        assertTrue(runtime.owns(source.name))
        // The manga runtime must not claim it even though both read the same registry.
        val mihon = MihonSourceRuntime(registry)
        assertFalse(mihon.owns(source.name))
        assertEquals(emptyList<SourceRef>(), mihon.getSources())
    }

    @Test
    fun `lists search and filters use the anime entry points with zero based offsets`() = run { _, _, client ->
        val source = client.getSources().single().name
        val described = client.describe(source)
        assertEquals(setOf("POPULARITY", "UPDATED"), described.sortOrders)
        assertEquals(SourcePagingMode.PAGE_INDEX, described.pagingMode)
        assertFalse(described.isChapterContentSupported)
        val popular = client.getList(source, 0, null, null).single()
        assertEquals("popular 1", popular.title)
        assertEquals("https://anime.invalid/cover.jpg", popular.coverUrl)
        assertEquals(setOf("Action", "Ecchi"), popular.tags.map { it.title }.toSet())
        assertEquals("ADULT", popular.contentRating)
        assertEquals("ONGOING", popular.state)
        assertEquals("popular 3", client.getList(source, 2, "POPULARITY", null).single().title)
        assertEquals("latest 2", client.getList(source, 1, "UPDATED", null).single().title)
        assertEquals("sora 1 dub=false", client.getList(source, 0, null, SourceFilter(query = "sora")).single().title)

        val filters = client.getDynamicFilters(source)
        val dub = MihonFilterRules.flatten(filters.nodes).single { it.name == "Dub" }
        assertEquals(SourceFilterKind.CHECKBOX, dub.kind)
        val searched = client.getList(
            source, 0, null,
            SourceFilter(query = "sora", dynamicFilters = listOf(SourceFilterChange(dub.id, SourceFilterValue.Toggle(true)))),
        ).single()
        assertEquals("sora 1 dub=true", searched.title)
        // The extension's own filter state is put back after a search.
        assertEquals("sora 1 dub=false", client.getList(source, 0, null, SourceFilter(query = "sora")).single().title)
    }

    @Test
    fun `details keep identity and list episodes oldest first with missing numbers filled from their position`() = run { _, _, client ->
        val source = client.getSources().single().name
        val seed = client.getList(source, 0, null, null).single()
        val details = client.getDetails(seed, SourceDetailsFetchMode.FORCE_REFRESH)
        assertEquals(seed.id, details.id)
        assertEquals(seed.url, details.url)
        assertEquals("About the anime", details.description)
        assertEquals("FINISHED", details.state)
        assertEquals(seed.coverUrl, details.coverUrl)
        val episodes = requireNotNull(details.chapters)
        // Listed newest first: Hosters(3), First(missing), Failing(0); reversed they are numbered 1, 2 (index) and 3.
        assertEquals(listOf("Failing", "First", "Hosters"), episodes.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), episodes.map { it.number })
        assertEquals("Fansub", episodes.first().scanlator)
        assertEquals(1720000000000L, episodes.first().uploadDate)
    }

    @Test
    fun `an episode's streams carry their headers tracks and quality and keep the preferred one first`() = run { _, _, client ->
        val source = client.getSources().single().name
        val details = client.getDetails(client.getList(source, 0, null, null).single(), SourceDetailsFetchMode.ALLOW_CACHE)
        val first = details.chapters!!.single { it.title == "First" }
        val pages = client.getPages(first, null)
        // The blank-URL video is dropped and the preferred 720p stream is offered before 360p.
        assertEquals(listOf("720p", "360p"), pages.map { it.playbackLabel })
        assertEquals(listOf(720, 360), pages.map { it.playbackQuality })
        val best = pages.first()
        assertEquals("https://cdn.invalid/720.m3u8", best.url)
        assertEquals(mapOf("Referer" to "https://anime.invalid/", "X-Token" to "t"), best.headers)
        assertEquals(listOf("https://anime.invalid/en.vtt"), best.externalSubtitleTracks.map { it.url })
        assertEquals(listOf("en"), best.externalSubtitleTracks.map { it.lang })
        assertEquals(listOf("https://anime.invalid/jp.aac"), best.externalAudioTracks.map { it.url })
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertEquals(best.url, client.getPageUrl(best))
    }

    @Test
    fun `an episode without plain videos falls back to its hosters inline and lazy`() = run { _, _, client ->
        val source = client.getSources().single().name
        val details = client.getDetails(client.getList(source, 0, null, null).single(), SourceDetailsFetchMode.ALLOW_CACHE)
        val hosters = details.chapters!!.single { it.title == "Hosters" }
        assertEquals(
            listOf("https://cdn.invalid/inline.mp4", "https://cdn.invalid/lazy.mp4"),
            client.getPages(hosters, null).map { it.url },
        )
        // No hoster list either: the plain list's failure is what the caller sees.
        val failing = details.chapters!!.single { it.title == "Failing" }
        assertNotNull(assertThrows(SourceRemoteException::class.java) { runBlocking { client.getPages(failing, null) } })
    }

    @Test
    fun `an episode that the session never listed is rebuilt from the portable chapter`() = run { _, _, client ->
        val source = client.getSources().single()
        val chapter = SourceChapter(1, "First", 1f, 0, "/ep/1", null, 0, null, source)
        assertEquals(listOf("720p", "360p"), client.getPages(chapter, null).map { it.playbackLabel })
    }
}
