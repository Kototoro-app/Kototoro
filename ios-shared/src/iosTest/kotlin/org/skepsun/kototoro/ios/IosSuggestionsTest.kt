@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.community.domain.*
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
                    assertTrue(call.order in setOf("POPULARITY", "UPDATED"))
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
            assertTrue(library.suggestions.items(sources, settings.copy(language = "ENGLISH", languageOnly = true)).isEmpty())
            assertEquals(cached.size, library.suggestions.items(sources,
                settings.copy(language = "CHINESE", languageOnly = true)).size)
            assertTrue(library.suggestions.items(sources, settings.copy(excludedTags = "爱情")).isEmpty())
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
    fun communityOptInMatchesAnIndexedWorkAndOffHidesStoredCommunityRows() = withDirectory { directory ->
        val seed = content(1, manga).copy(title = "Favourite Work")
        val similar = content(101, manga).copy(title = "Another Work")
        val key = checkNotNull(CommunityWorkKeys.titleKey(seed.title, CommunityMedium.COMIC))
        val keyPath = CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(key))
        val seedPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of("seed"))
        val similarPath = CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of("similar"))
        val index = CommunitySnapshotManifest(
            generatedAt = 1, relays = emptyList(), powRating = 0, powComment = 0, minChartWeight = 5.0,
            files = setOf(keyPath, seedPath, similarPath).associateWith { "hash" },
            stats = CommunitySnapshotStats(0, 0, 0, 0, 2),
        )
        val seedSummary = CommunityWorkSummary("seed", "comic", seed.title, similar = listOf(
            CommunitySimilarWork("similar", similar.title, 0.9, 5),
        ))
        val summary = CommunityWorkSummary("similar", "comic", similar.title)
        val shards = mapOf("seed" to seedSummary, "similar" to summary).entries
            .groupBy { CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(it.key)) }
            .mapValues { (_, entries) -> Json.encodeToString(entries.associate { it.key to it.value }) }
        val files = shards + mapOf(
            CommunitySnapshotShards.MANIFEST_PATH to Json.encodeToString(index),
            keyPath to Json.encodeToString(mapOf(key to "seed")),
        )
        val onlyManga = sources.take(1)
        val runtime = IosSources(Fixture { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(SourceDescriptor(
                    manga, setOf("POPULARITY"), "POPULARITY",
                    SourceFilterCapabilities(true, false, false, false, false, false, false, false), SourcePagingMode.OFFSET))
                is SourceCall.Filters -> error("No tags")
                is SourceCall.ListContent -> SourceResult.ListContent(
                    if (call.filter?.query == similar.title) listOf(similar) else listOf(content(102)),
                )
                else -> error("Unexpected request $call")
            }
        })
        val library = IosLibrary("$directory/library.db")
        try {
            library.setFavourite(IosSourceContent(seed), true)
            var requests = 0
            val recommendations = IosSuggestions(library) { path -> requests++; files[path] }
            recommendations.refresh(runtime, onlyManga, settings, IosCancellation())
            assertEquals(0, requests)
            val optedIn = settings.copy(communityEnabled = true)
            recommendations.refresh(runtime, onlyManga, optedIn, IosCancellation())
            val item = recommendations.items(onlyManga, optedIn).single { it.suggestionDirection == "COMMUNITY" }
            assertEquals(similar.title, item.title)
            assertEquals(seed.title, item.suggestionReason)
            assertEquals(listOf("102"), recommendations.items(onlyManga, settings).map { it.id })
        } finally { library.close(); runtime.close() }
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
