package org.skepsun.kototoro.search.ui.suggestion

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SearchSuggestionType
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.search.domain.LibrarySearchScope
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingEntitySearchResult
import org.skepsun.kototoro.tracking.discovery.domain.EntityType
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionViewModelTest {

    private val store = ViewModelStore()
    private val repository = mockk<ContentSearchRepository>()
    private val settings = mockk<AppSettings>()
    private val sources = mockk<ContentSourcesRepository>()
    private val tracking = mockk<TrackingSiteDiscoveryService>()
    private val preferredSite = object : PreferredTrackingSiteProvider {
        override val preferredSite = flowOf(ScrobblerService.MAL)

        override fun getPreferredSite() = ScrobblerService.MAL

        override fun setPreferredSite(service: ScrobblerService) = Unit
    }
    private val favourites = mockk<GlobalFavoritesState>()
    private val history = mockk<HistoryRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.activeSourcePresetId } returns 0L
        every { settings.isIncognitoModeEnabled } returns false
        every { settings.searchSuggestionTypes } returns setOf(SearchSuggestionType.QUERIES_RECENT)
        every { settings.observeChanges() } returns emptyFlow()
        every { sources.observeEnabledSources() } returns MutableStateFlow<List<ContentSourceInfo>>(emptyList())
        every { favourites.selectedSourceTags } returns MutableStateFlow(emptySet())
        coEvery { repository.getQuerySuggestion(any(), any()) } answers { listOf(firstArg()) }
        coEvery { repository.getContentSuggestion(any(), any(), any()) } returns emptyList()
        coEvery { tracking.search(any()) } coAnswers { awaitCancellation() }
        coEvery { tracking.searchEntities(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `surrounding input whitespace does not hide local suggestions behind a slow remote search`() = runBlocking {
        val model = model()
        model.onQueryChanged("  Slider571 \t")

        val suggestions = withTimeout(10_000) { model.suggestion.first() }

        assertEquals(listOf(SearchSuggestionItem.RecentQuery("Slider571")), suggestions)
        coVerify(exactly = 1) { repository.getQuerySuggestion("Slider571", any()) }
    }

    @Test
    fun `whitespace only input loads recent suggestions without a remote request`() = runBlocking {
        coEvery { repository.getQuerySuggestion(any(), any()) } returns listOf("recent")
        val model = model()
        model.onQueryChanged(" \t ")

        val suggestions = withTimeout(10_000) { model.suggestion.first() }

        assertEquals(listOf(SearchSuggestionItem.RecentQuery("recent")), suggestions)
        coVerify(exactly = 1) { repository.getQuerySuggestion("", any()) }
        coVerify(exactly = 1) { repository.getContentSuggestion("", any(), null) }
        coVerify(exactly = 0) { tracking.search(any()) }
        coVerify(exactly = 0) { tracking.searchEntities(any(), any(), any(), any()) }
    }

    @Test
    fun `failed online suggestions report an error while keeping local matches`() = runBlocking {
        coEvery { tracking.search(any()) } throws IOException("offline")
        coEvery { tracking.searchEntities(any(), any(), any(), any()) } throws IOException("offline")
        val model = model()
        model.onQueryChanged("query")

        val state = withTimeout(10_000) { model.suggestionState.first { it.remoteError != null } }

        assertEquals(listOf(SearchSuggestionItem.RecentQuery("query")), state.items)
        assertFalse(state.isRemoteLoading)
    }

    @Test
    fun `new input shows a clean loading state before the debounce finishes`() = runBlocking {
        val model = model()
        model.onQueryChanged("new query")

        val state = withTimeout(10_000) { model.suggestionState.first() }

        assertEquals("new query", state.query)
        assertTrue(state.isLoading)
        assertTrue(state.items.isEmpty())
        coVerify(exactly = 0) { repository.getQuerySuggestion(any(), any()) }
        coVerify(exactly = 0) { tracking.search(any()) }
    }

    @Test
    fun `a failed work request does not discard successful person suggestions`() = runBlocking {
        coEvery { tracking.search(any()) } throws IOException("offline")
        coEvery { tracking.searchEntities(any(), any(), any(), any()) } returns emptyList()
        coEvery { tracking.searchEntities(ScrobblerService.MAL, EntityType.PERSON, any(), any()) } returns listOf(
            TrackingEntitySearchResult(ScrobblerService.MAL, EntityType.PERSON, 1L, "person"),
        )
        val model = model()
        model.onQueryChanged("query")

        val state = withTimeout(10_000) {
            model.suggestionState.first { !it.isLoading && !it.isRemoteLoading }
        }

        assertEquals(null, state.remoteError)
        val remote = state.items.filterIsInstance<SearchSuggestionItem.TrackingEntityList>().single()
        assertEquals("person", remote.items.single().name)
        assertEquals(EntityType.PERSON, remote.items.single().entityType)
    }

    @Test
    fun `changing the query cancels old online work before waiting for the new debounce`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { tracking.search(any()) } coAnswers {
            val catalog = firstArg<TrackingSiteCatalog>()
            if (catalog.query == "old") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            } else {
                awaitCancellation()
            }
        }
        val model = model()
        model.onQueryChanged("old")
        val states = Channel<SearchSuggestionState>(Channel.UNLIMITED)
        val collection = launch { model.suggestionState.collect { states.send(it) } }
        try {
            withTimeout(10_000) { started.await() }
            model.onQueryChanged("new")
            val fresh = withTimeout(10_000) {
                var state = states.receive()
                while (state.query != "new") state = states.receive()
                state
            }
            assertTrue(fresh.isLoading)
            assertTrue(fresh.items.isEmpty())
            withTimeout(10_000) { cancelled.await() }
        } finally {
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `recent sources keep disabled entries so they can be switched back on`() = runBlocking {
        val enabled = TestSource("ENABLED_SOURCE")
        val disabled = TestSource("DISABLED_SOURCE")
        every { settings.searchSuggestionTypes } returns setOf(SearchSuggestionType.RECENT_SOURCES)
        every { sources.observeEnabledSources() } returns MutableStateFlow(
            listOf(ContentSourceInfo(enabled, isEnabled = true, isPinned = false)),
        )
        coEvery { repository.getSourcesSuggestion(any<Int>()) } returns listOf(disabled, enabled)
        val model = model(SourceTypeIdentifier())

        val suggestions = withTimeout(10_000) { model.suggestion.first() }

        assertEquals(
            listOf(
                SearchSuggestionItem.SourceTip(disabled, isEnabled = false),
                SearchSuggestionItem.SourceTip(enabled, isEnabled = true),
            ),
            suggestions,
        )
    }

    @Test
    fun `history tab narrows suggestions to history matches and recent queries`() = runBlocking {
        val match = Content(
            id = 7L,
            title = "Frieren",
            altTitles = emptySet(),
            url = "/frieren",
            publicUrl = "https://example.com/frieren",
            rating = -1f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = TestSource("HISTORY_SOURCE"),
        )
        coEvery { history.searchLibrary("frie", any()) } returns listOf(match)
        coEvery { repository.getQuerySuggestion(any(), any()) } returns listOf("frieren")
        val model = model()
        model.setLibraryScope(LibrarySearchScope.HISTORY)
        model.onQueryChanged("frie")

        val state = withTimeout(10_000) { model.suggestionState.first { !it.isLoading } }

        assertEquals(LibrarySearchScope.HISTORY, state.libraryScope)
        assertEquals(
            listOf(
                SearchSuggestionItem.LibraryMatch(LibrarySearchScope.HISTORY, match),
                SearchSuggestionItem.RecentQuery("frieren"),
            ),
            state.items,
        )
        coVerify(exactly = 0) { repository.getContentSuggestion(any(), any(), any()) }
        coVerify(exactly = 0) { tracking.search(any()) }
    }

    @Test
    fun `pages without previews only offer recent queries`() = runBlocking {
        coEvery { repository.getQuerySuggestion(any(), any()) } returns listOf("isekai")
        val model = model()
        model.setLibraryScope(LibrarySearchScope.UPDATES)
        model.onQueryChanged("ise")

        val state = withTimeout(10_000) { model.suggestionState.first { !it.isLoading } }

        assertEquals(LibrarySearchScope.UPDATES, state.libraryScope)
        assertEquals(listOf(SearchSuggestionItem.RecentQuery("isekai")), state.items)
        coVerify(exactly = 0) { repository.getContentSuggestion(any(), any(), any()) }
    }

    @Test
    fun `confirmed queries are recorded outside incognito mode`() {
        every { repository.saveSearchQuery(any()) } returns Unit

        model().saveQuery("query")

        verify(exactly = 1) { repository.saveSearchQuery("query") }
    }

    @Test
    fun `confirmed queries are not recorded in incognito mode`() {
        every { settings.isIncognitoModeEnabled } returns true

        model().saveQuery("private query")

        verify(exactly = 0) { repository.saveSearchQuery(any()) }
    }

    private data class TestSource(override val name: String) : ContentSource {
        override val locale = ""
        override val contentType = ContentType.MANGA
    }

    private fun model(
        sourceTypeIdentifier: SourceTypeIdentifier = mockk<SourceTypeIdentifier>(),
    ) = SearchSuggestionViewModel(
        repository = repository,
        settings = settings,
        sourcesRepository = sources,
        sourcePresetsRepository = mockk<SourcePresetsRepository>(),
        sourceTypeIdentifier = sourceTypeIdentifier,
        globalFavoritesState = favourites,
        trackingSiteDiscoveryService = tracking,
        preferredTrackingSiteProvider = preferredSite,
        historyRepository = history,
        favouritesRepository = mockk(),
        localMangaRepository = mockk(),
        suggestionRepository = mockk(),
    ).also { store.put("suggestions", it) }
}
