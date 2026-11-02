@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.*
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

class IosSuggestionsTest {
    private val manga = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val novels = SourceRef("NOVELS", "zh", "NOVEL")
    private val broken = SourceRef("BROKEN", "zh", "MANGA")
    private val adult = SourceRef("ADULT", "zh", "HENTAI_MANGA")
    private val sources = listOf(manga, novels, broken, adult).map {
        IosSource(it.name, it.name, it.locale, it.contentType, "extension")
    }
    private val settings = IosSuggestionSettings(true, "", "Horror", emptyList(), emptyList())

    private fun content(id: Long, source: SourceRef = manga, vararg tags: String) = SourceContent(
        id, "作品 $id", emptySet(), "/work/$id", "https://fixture.invalid/$id", 0f, null, null,
        tags.map { SourceTag(it, it.lowercase(), source) }.toSet(), null, emptySet(), source)

    private class Fixture(val answer: (SourceCall) -> SourceResult) : IosSourceTransport {
        override fun close() = Unit

        override fun exchange(requestJson: String): String {
            val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
            return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, answer(request.call)))
        }
    }

    @Test
    fun seedsFilteringFallbackBalanceAndPersistenceFollowSharedRules() = withDirectory { directory ->
        val runtime = IosSources(Fixture { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(SourceDescriptor(
                    sources.first { it.name == call.sourceName }
                        .let { SourceRef(it.name, it.locale, it.contentType) },
                    setOf("POPULARITY", "UPDATED"), "POPULARITY",
                    SourceFilterCapabilities(true, false, true, true, false, false, false, false), SourcePagingMode.OFFSET))
                is SourceCall.Filters -> SourceResult.Filters(SourceFilterOptions(
                    availableTags = setOf(SourceTag("Romance", "romance", manga)),
                    tagGroups = emptyList(),
                    availableStates = emptySet(), availableContentRating = emptySet(), availableContentTypes = emptySet(),
                    availableDemographics = emptySet(), availableLocales = emptySet()))
                is SourceCall.ListContent -> {
                    assertEquals("UPDATED", call.order)
                    check(call.sourceName != adult.name) { "Adult source must not be queried" }
                    if (call.sourceName == broken.name) error("offline")
                    if (call.filter?.tags?.isNotEmpty() == true) {
                        assertEquals("Romance", call.filter?.tags?.single()?.title)
                        SourceResult.ListContent(emptyList()) // A tagged empty page falls back to the source's list.
                    } else {
                        val source = if (call.sourceName == manga.name) manga else novels
                        val base = if (source == manga) 100L else 200L
                        SourceResult.ListContent((0L..15L).map { content(base + it, source, "Romance") } +
                            content(base + 40, source, "Horror") +
                            content(base + 41, source, "Romance").copy(title = " ") +
                            content(base + 42, source, "Romance").copy(contentRating = "ADULT"))
                    }
                }
                else -> error("Unexpected request $call")
            }
        })
        val library = IosLibrary("$directory/library.db")
        try {
            library.setFavourite(IosSourceContent(content(1, manga, "Romance")), true)
            // A list response must preserve the richer stored row, favourite and opaque source payload.
            library.setFavourite(IosSourceContent(content(100, manga, "Romance").copy(description = "保留简介",
                sourceData = "opaque")), true)
            assertEquals(24, library.suggestions.refresh(runtime, sources, settings, IosCancellation()))
            val cached = library.suggestions.items(sources, settings)
            assertEquals(12, cached.count { it.sourceName == manga.name })
            assertEquals(12, cached.count { it.sourceName == novels.name })
            assertTrue(cached.none { "Horror" in it.tags || it.contentRating == "ADULT" || it.title.isBlank() })
            val stored = library.favourites().single { it.content.id == "100" }.content
            assertEquals("保留简介", stored.summary)
            assertEquals("opaque", stored.value.sourceData)
            assertTrue(library.suggestions.items(sources, settings.copy(excludedSources = listOf(novels.name)))
                .all { it.mediaKind == "MANGA" })
            assertTrue(library.suggestions.items(emptyList(), settings).isEmpty())
        } finally { library.close(); runtime.close() }
        val reopened = IosLibrary("$directory/library.db")
        try { assertEquals(24, reopened.suggestions.items(sources, settings).size) }
        finally { reopened.close() }
    }

    @Test
    fun allFailedOrCancelledRequestsKeepPreviousCacheAndPreferencesCanSeedANewUser() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val good = IosSources(Fixture { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(SourceDescriptor(
                    manga, setOf("UPDATED"), "UPDATED",
                    SourceFilterCapabilities(false, false, false, false, false, false, false, false), SourcePagingMode.OFFSET))
                is SourceCall.Filters -> error("unsupported filters")
                is SourceCall.ListContent -> SourceResult.ListContent(listOf(content(101, manga, "Romance")))
                else -> error("Unexpected request")
            }
        })
        val bad = IosSources(Fixture { error("offline") })
        val onlyManga = sources.take(1)
        val preferenceSeed = settings.copy(preferredTags = "Romance")
        try {
            assertEquals(1, library.suggestions.refresh(good, onlyManga, preferenceSeed, IosCancellation()))
            assertFailsWith<IllegalStateException> {
                library.suggestions.refresh(bad, onlyManga, preferenceSeed, IosCancellation())
            }
            assertEquals(listOf("101"), library.suggestions.items(onlyManga, settings).map { it.id })
            val cancellation = IosCancellation().also { it.cancel() }
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                library.suggestions.refresh(good, onlyManga, preferenceSeed, cancellation)
            }
            assertEquals(listOf("101"), library.suggestions.items(onlyManga, settings).map { it.id })
        } finally { library.close(); good.close(); bad.close() }
    }

    private fun withDirectory(block: suspend (String) -> Unit) = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        try { block(directory) } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }
}
