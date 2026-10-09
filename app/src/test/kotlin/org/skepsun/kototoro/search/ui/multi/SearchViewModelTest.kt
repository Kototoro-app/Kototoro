package org.skepsun.kototoro.search.ui.multi

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchFilters
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchResults
import org.skepsun.kototoro.search.domain.SearchV2Helper
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val store = ViewModelStore()
    private val settings = mockk<AppSettings>()
    private val sources = mockk<ContentSourcesRepository>()
    private val presets = mockk<SourcePresetsRepository>()
    private val identifier = mockk<SourceTypeIdentifier>()
    private val history = mockk<HistoryRepository>()
    private val favourites = mockk<FavouritesRepository>()
    private val mapper = mockk<ContentListMapper>()
    private val factory = mockk<SearchV2Helper.Factory>()
    private val firstSource = source("first")
    private val secondSource = source("second")
    private val firstHelper = mockk<SearchV2Helper>()
    private val secondHelper = mockk<SearchV2Helper>()
    private val localHelper = mockk<SearchV2Helper>()
    private var presetId = 0L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.activeSourcePresetId } answers { presetId }
        every { settings.activeSourcePresetId = any() } answers { presetId = firstArg() }
        every { settings.globalTagBlacklist } returns emptySet()
        every { settings.observeChanges() } returns emptyFlow()
        every { presets.observeAll() } returns flowOf(emptyList())
        every { identifier.getSourceType(any()) } returns SourceType.NATIVE
        coEvery { sources.getEnabledSources() } returns listOf(firstSource, secondSource)
        coEvery { sources.getPinnedSources() } returns setOf(firstSource, secondSource)
        coEvery { sources.getDisabledSources() } returns emptySet()
        coEvery { history.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { favourites.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { mapper.toListModelList(any<Collection<Content>>(), any(), any()) } returns
            listOf(mockk<ContentListModel>(relaxed = true))
        every { factory.create(firstSource) } returns firstHelper
        every { factory.create(secondSource) } returns secondHelper
        every { factory.create(LocalMangaSource) } returns localHelper
        coEvery { localHelper.invoke(any(), any(), any()) } returns null
        coEvery { firstHelper.invoke(any(), any(), any()) } returns result()
        coEvery { secondHelper.invoke(any(), any(), any()) } returns result()
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `retry searches only the failed source and preserves successful results`() = runBlocking {
        coEvery { firstHelper.invoke(any(), any(), any()) } throws IOException("failed")
        val model = model()
        val initial = model.awaitIdle()
        assertEquals(2, initial.completedSources)
        assertEquals(2, initial.totalSources)
        assertEquals(1, initial.failedSources)
        val successful = initial.sections.first { it.source == secondSource }
        coEvery { firstHelper.invoke(any(), any(), any()) } returns result()

        model.retrySource(initial.sections.first { it.source == firstSource })
        val retried = model.awaitIdle()

        assertEquals(0, retried.failedSources)
        assertEquals(successful, retried.sections.first { it.source == secondSource })
        coVerify(exactly = 2) { firstHelper.invoke(any(), any(), any()) }
        coVerify(exactly = 1) { secondHelper.invoke(any(), any(), any()) }
        coVerify(exactly = 1) { history.search(any(), any(), any(), any()) }
    }

    @Test
    fun `applying several filters starts a single search with the complete scope`() = runBlocking {
        val model = model()
        model.awaitIdle()
        val preset = mockk<SourcePreset>()
        every { preset.sources } returns setOf(firstSource.name)
        coEvery { presets.getById(9L) } returns preset

        model.applyFilters(
            SearchFilters(
                sourceTypes = setOf(SourceType.NATIVE),
                contentKinds = setOf(SearchContentKind.MANGA),
                pinnedOnly = true,
                languagePresetId = 9L,
            ),
        )
        model.awaitIdle()

        assertEquals(9L, presetId)
        coVerify(exactly = 1) { sources.getEnabledSources() }
        coVerify(exactly = 1) { sources.getPinnedSources() }
        coVerify(exactly = 1) { presets.getById(9L) }
        coVerify(exactly = 2) { firstHelper.invoke(any(), any(), any()) }
        coVerify(exactly = 1) { secondHelper.invoke(any(), any(), any()) }
        assertFalse(model.searchState.value.canSearchDisabledSources)
    }

    @Test
    fun `hiding empty sources does not restart a search even with a zero preset id`() = runBlocking {
        val model = model()
        model.awaitIdle()
        model.applyFilters(model.filters.value.copy(hideEmpty = true))
        assertFalse(model.searchState.value.isSearching)
        assertTrue(model.filters.value.hideEmpty)
        coVerify(exactly = 1) { sources.getEnabledSources() }
    }

    @Test
    fun `advanced criteria reach favourites and history even when the main query is empty`() = runBlocking {
        val advanced = AdvancedSearchParams(title = "Naruto")
        coEvery { favourites.search("", SearchKind.ADVANCED, any(), advanced) } returns listOf(content())

        val model = model(query = "", kind = SearchKind.ADVANCED, advancedTitle = "Naruto")
        val state = model.awaitIdle()

        assertTrue(state.sections.any { it.titleResId == org.skepsun.kototoro.R.string.favourites })
        coVerify(exactly = 1) { favourites.search("", SearchKind.ADVANCED, Int.MAX_VALUE, advanced) }
        coVerify(exactly = 1) { history.search("", SearchKind.ADVANCED, Int.MAX_VALUE, advanced) }
    }

    @Test
    fun `a changed scope cancels the previous search without leaking its progress or results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { firstHelper.invoke(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val model = model()
        withTimeout(10_000) { started.await() }

        model.applyFilters(model.filters.value.copy(contentKinds = setOf(SearchContentKind.VIDEO)))
        val state = model.awaitIdle()

        assertTrue(cancelled.isCompleted)
        assertEquals(0, state.totalSources)
        assertEquals(0, state.completedSources)
        assertTrue(state.sections.isEmpty())
    }

    private fun model(
        query: String = "query",
        kind: SearchKind = SearchKind.SIMPLE,
        advancedTitle: String = "",
    ) = SearchViewModel(
        query = query,
        kind = kind,
        advancedTitle = advancedTitle,
        advancedTags = "",
        advancedAuthor = "",
        initialPinnedOnly = false,
        initialHideEmpty = false,
        sourceTypeNames = ALL_SOURCE_TYPES.map { it.name },
        contentKindNames = null,
        mangaListMapper = mapper,
        searchHelperFactory = factory,
        sourcesRepository = sources,
        sourceTypeIdentifier = identifier,
        appSettings = settings,
        sourcePresetsRepository = presets,
        globalFavoritesState = mockk<GlobalFavoritesState>(),
        historyRepository = history,
        favouritesRepository = favourites,
    ).also { store.put("search", it) }

    private suspend fun SearchViewModel.awaitIdle() = withTimeout(10_000) {
        searchState.first { !it.isSearching }
    }

    private fun result() = SearchResults(
        listFilter = ContentListFilter(query = "query"),
        sortOrder = SortOrder.RELEVANCE,
        manga = listOf(content()),
    )

    private fun content() = Content(
        id = 1L,
        title = "Naruto",
        altTitles = emptySet(),
        url = "/work/1",
        publicUrl = "https://example.test/work/1",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = firstSource,
    )

    private fun source(name: String) = object : ContentSource {
        override val name = name
        override val locale = ""
        override val contentType = ContentType.MANGA
    }
}
