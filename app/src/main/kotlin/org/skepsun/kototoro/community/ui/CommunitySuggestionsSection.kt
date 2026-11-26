package org.skepsun.kototoro.community.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.community.data.CachedCover
import org.skepsun.kototoro.community.data.CommunityCover
import org.skepsun.kototoro.community.data.CommunityCoverCache
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunitySuggestionsRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.community.domain.CommunityWorkFinder
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

/** The suggestions page's community chart; empty while the community is off. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunitySuggestionsViewModel @Inject constructor(
    private val repository: CommunitySuggestionsRepository,
    private val finder: CommunityWorkFinder,
    private val coverCache: CommunityCoverCache,
    settings: AppSettings,
) : ViewModel() {

    private val window = MutableStateFlow(CommunitySuggestionsUiState.WINDOWS.first())
    private val enabled = settings.observeAsFlow(
        setOf(AppSettings.KEY_COMMUNITY_ENABLED, AppSettings.KEY_COMMUNITY_NSFW),
    ) { isCommunityEnabled to isCommunityNsfwRatingEnabled }

    /** Covers of chart works that a source of the reader's has, by work id; the cache keeps them between visits. */
    private val covers = MutableStateFlow<Map<String, CommunityCover>>(emptyMap())
    private val coverLookups = HashSet<String>()
    private val coverPermits = Semaphore(COVER_LOOKUPS_AT_ONCE)

    val state: StateFlow<CommunitySuggestionsUiState> = combine(
        enabled.flatMapLatest { (on, _) ->
            if (!on) flowOf(CommunitySuggestionsUiState()) else window.flatMapLatest { period ->
                flow { emit(load(period)) }
            }
        }.onEach { requestCovers(it.entries) },
        covers,
    ) { chart, found -> if (found.isEmpty()) chart else chart.copy(covers = found) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunitySuggestionsUiState())

    /**
     * The chart names works only by title, so a card has no cover of its own. What the cache has is shown at once; the
     * best ranked works it knows nothing about are looked up in the reader's sources ([CommunityWorkFinder]), a few at a
     * time, and what the lookup finds or fails to find is cached for the next visit.
     */
    private fun requestCovers(entries: List<CommunityChartEntry>) {
        entries.take(COVER_LOOKUP_LIMIT).forEach { entry ->
            val title = entry.title?.takeIf { it.isNotBlank() } ?: return@forEach
            when (val cached = coverCache.get(entry.id)) {
                is CachedCover.Found -> covers.update { it + (entry.id to cached.cover) }
                CachedCover.Missing -> Unit
                null -> {
                    synchronized(coverLookups) { if (!coverLookups.add(entry.id)) return@forEach }
                    viewModelScope.launch(Dispatchers.IO) {
                        val found = coverPermits.withPermit { finder.find(entry.id, title) }
                        val url = found?.coverUrl?.takeIf { it.isNotBlank() }
                        if (found != null && url != null) {
                            val cover = CommunityCover(found.source.name, url)
                            coverCache.putFound(entry.id, cover)
                            covers.update { it + (entry.id to cover) }
                        } else {
                            coverCache.putMissing(entry.id)
                        }
                    }
                }
            }
        }
    }

    /** A chart work opened: the reader's copy or a source's, else the title search ([CommunityWorkFinder]). */
    fun openWork(id: String, title: String, onFound: (Content) -> Unit, onMissing: (String) -> Unit) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { finder.find(id, title) }
            if (found != null) onFound(found) else onMissing(title)
        }
    }

    fun selectWindow(value: String) {
        window.value = value
    }

    private suspend fun load(window: String): CommunitySuggestionsUiState {
        val popular = attempt { repository.popular(window) }
        if (popular.isNotEmpty()) return CommunitySuggestionsUiState(true, window, popular)
        // A quiet week next to a filled all-time chart is still a chart; only an index without any is too small.
        val charted = window != CommunityHighlights.WINDOW_ALL && attempt { repository.popular(CommunityHighlights.WINDOW_ALL) }.isNotEmpty()
        return if (charted) {
            CommunitySuggestionsUiState(true, window)
        } else {
            CommunitySuggestionsUiState(true, window, attempt { repository.rated() }, isFallback = true)
        }
    }

    private suspend fun attempt(block: suspend () -> List<CommunityChartEntry>) =
        runCatchingCancellable { block() }.getOrDefault(emptyList())

    private companion object {
        const val COVER_LOOKUP_LIMIT = 12
        const val COVER_LOOKUPS_AT_ONCE = 3
    }
}

/** The suggestions page's community chart, drawn by the shared [CommunityChartSection]. */
@Composable
fun CommunitySuggestionsSection(
    onSearchTitle: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    onOpenContent: (Content) -> Unit = {},
) {
    val viewModel = hiltViewModel<CommunitySuggestionsViewModel>()
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val showSources = rememberCommunitySources(onOpenContent, onSearchTitle)
    CommunityChartSection(
        state = state,
        onSelectWindow = viewModel::selectWindow,
        onOpenWork = { id, title -> viewModel.openWork(id, title, onOpenContent, onSearchTitle) },
        modifier = modifier,
        horizontalPadding = horizontalPadding,
        onShowSources = showSources,
        coverContent = { cover, coverModifier ->
            val context = LocalContext.current
            val located = cover as CommunityCover
            val request = remember(located) {
                ImageRequest.Builder(context).data(located.url).mangaSourceExtra(ContentSource(located.sourceName))
                    .crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        },
    )
}
