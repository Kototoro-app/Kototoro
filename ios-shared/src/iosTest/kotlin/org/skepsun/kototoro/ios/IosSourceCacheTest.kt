package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Protocol fixtures only; no sites, credentials or platform UI are involved. */
class IosSourceCacheTest {
    private val source = SourceRef("FIXTURE", "zh", "MANGA")
    private val tag = SourceTag("Action", "action", source)

    private fun descriptor(dynamic: Boolean = false, combined: Boolean = true) = SourceDescriptor(
        source, setOf("POPULARITY"), "POPULARITY",
        SourceFilterCapabilities(true, true, true, combined, false, false, false, false),
        SourcePagingMode.PAGE_INDEX, isDynamicFilteringSupported = dynamic,
    )

    private fun options(tags: Set<SourceTag> = setOf(tag)) = SourceFilterOptions(
        availableTags = tags, tagGroups = emptyList(), availableStates = emptySet(),
        availableContentRating = emptySet(), availableContentTypes = emptySet(),
        availableDemographics = emptySet(), availableLocales = emptySet(),
    )

    private fun selection() = IosFilterSelection(
        order = null, tagKeys = listOf(tag.key), excludedTagKeys = emptyList(), states = emptyList(),
        contentRatings = emptyList(), types = emptyList(), demographics = emptyList(), locale = null,
        originalLocale = null, year = 0, yearFrom = 0, yearTo = 0, author = null, dynamic = emptyList(),
    )

    private fun runtime(reply: (SourceCall) -> SourceResult): IosSources = IosSources(object : IosSourceTransport {
        override fun exchange(requestJson: String): String {
            val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
            return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, reply(request.call)))
        }
        override fun close() {}
    })

    @Test
    fun selectedTagsLoadTheirPanelAfterCookieInvalidation() = runBlocking {
        var filterLoads = 0
        val app = runtime { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(descriptor())
                is SourceCall.Filters -> { filterLoads++; SourceResult.Filters(options()) }
                is SourceCall.ImportCookies -> SourceResult.CookiesImported(1)
                is SourceCall.ListContent -> {
                    assertEquals(setOf(tag), call.filter?.tags)
                    SourceResult.ListContent(emptyList())
                }
                else -> error("Unexpected call: $call")
            }
        }
        try {
            app.filterPanel(source.name)
            app.importCookies(source.name, "https://fixture.invalid", listOf("session=fixture"))
            app.filteredPage(source.name, 0, 0, null, selection())
            assertEquals(2, filterLoads)
        } finally { app.close() }
    }

    @Test
    fun searchingWithoutCombinedFiltersDoesNotRequireThePanel() = runBlocking<Unit> {
        val app = runtime { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(descriptor(combined = false))
                is SourceCall.ListContent -> {
                    assertEquals(SourceFilter(query = "title"), call.filter)
                    SourceResult.ListContent(emptyList())
                }
                else -> error("Search must not load the filter panel: $call")
            }
        }
        try { app.filteredPage(source.name, 0, 0, "title", selection()) } finally { app.close() }
    }

    @Test
    fun anAdvertisedDynamicFilterFailureMustRemainRetryable() = runBlocking<Unit> {
        val app = runtime { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(descriptor(dynamic = true))
                is SourceCall.Filters -> SourceResult.Filters(options())
                is SourceCall.DynamicFilters -> error("fixture failure")
                else -> error("Unexpected call: $call")
            }
        }
        try {
            assertFailsWith<IllegalStateException> { app.filterPanel(source.name) }
        } finally { app.close() }
    }

    @Test
    fun anInvalidatedDescriptorCannotRepopulateTheCache() = runBlocking {
        var describes = 0
        lateinit var app: IosSources
        app = runtime { call ->
            check(call is SourceCall.Describe)
            describes++
            val value = descriptor().copy(homepage = if (describes == 1) "https://old.invalid" else "https://new.invalid")
            if (describes == 1) runBlocking { app.sourcesChanged() }
            SourceResult.Descriptor(value)
        }
        try {
            assertEquals("https://new.invalid", app.homepage(source.name))
            assertEquals("https://new.invalid", app.homepage(source.name))
            assertEquals(2, describes)
        } finally { app.close() }
    }

    @Test
    fun anInvalidatedPanelCannotRepopulateTheCache() = runBlocking {
        var filterLoads = 0
        val newTag = tag.copy(key = "new", title = "New")
        lateinit var app: IosSources
        app = runtime { call ->
            when (call) {
                is SourceCall.Describe -> SourceResult.Descriptor(descriptor())
                is SourceCall.Filters -> {
                    filterLoads++
                    val value = options(if (filterLoads == 1) setOf(tag) else setOf(newTag))
                    if (filterLoads == 1) runBlocking { app.sourcesChanged() }
                    SourceResult.Filters(value)
                }
                else -> error("Unexpected call: $call")
            }
        }
        try {
            assertEquals(listOf("new"), app.filterPanel(source.name).quickTags.map { it.key })
            assertEquals(2, filterLoads)
        } finally { app.close() }
    }
}
