package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunitySuggestionsRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.chips.CompactChoicePill
import org.skepsun.kototoro.core.ui.chips.choicePillPosition
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class CommunitySuggestionsUiState(
    /** False until the index answered, or while the community is off. */
    val isLoaded: Boolean = false,
    val window: String = WINDOWS.first(),
    val entries: List<CommunityChartEntry> = emptyList(),
    /** No chart has entries in any window yet, so [entries] lists every rated work rather than a ranking. */
    val isFallback: Boolean = false,
) {
    companion object {
        val WINDOWS = listOf("week", "month", CommunityHighlights.WINDOW_ALL)
    }
}

/** The suggestions page's community chart; empty while the community is off. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunitySuggestionsViewModel @Inject constructor(
    private val repository: CommunitySuggestionsRepository,
    settings: AppSettings,
) : ViewModel() {

    private val window = MutableStateFlow(CommunitySuggestionsUiState.WINDOWS.first())
    private val enabled = settings.observeAsFlow(
        setOf(AppSettings.KEY_COMMUNITY_ENABLED, AppSettings.KEY_COMMUNITY_NSFW),
    ) { isCommunityEnabled to isCommunityNsfwRatingEnabled }

    val state: StateFlow<CommunitySuggestionsUiState> = enabled.flatMapLatest { (on, _) ->
        if (!on) flowOf(CommunitySuggestionsUiState()) else window.flatMapLatest { period ->
            flow { emit(load(period)) }
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunitySuggestionsUiState())

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
}

/**
 * The community chart on the suggestions page. While the index is too small for any chart, it lists every work readers
 * rated instead, so the community is visible from its first ratings. Draws nothing when there is nothing to list.
 */
@Composable
fun CommunitySuggestionsSection(
    onSearchTitle: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val viewModel = hiltViewModel<CommunitySuggestionsViewModel>()
    val state = viewModel.state.collectAsStateWithLifecycle().value
    if (!state.isLoaded || (state.isFallback && state.entries.isEmpty())) return
    CommunityWorksRail(
        title = stringResource(if (state.isFallback) R.string.community_rated else R.string.community_popular),
        cards = state.entries.map { entry ->
            CommunityWorkCard(
                key = entry.id,
                title = entry.title.orEmpty(),
                supportingText = stringResource(R.string.community_rating_count, entry.count),
                score = entry.score,
            )
        },
        onOpen = { onSearchTitle(it.title) },
        horizontalPadding = horizontalPadding,
        ranked = !state.isFallback,
        note = if (state.isFallback) stringResource(R.string.community_rated_note) else null,
        emptyText = stringResource(R.string.community_chart_empty),
        titleTrailing = if (state.isFallback) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val windows = CommunitySuggestionsUiState.WINDOWS
                    windows.forEachIndexed { index, window ->
                        CompactChoicePill(
                            selected = state.window == window,
                            onClick = { viewModel.selectWindow(window) },
                            label = stringResource(
                                when (window) {
                                    "month" -> R.string.community_window_month
                                    CommunityHighlights.WINDOW_ALL -> R.string.community_window_all
                                    else -> R.string.community_window_week
                                },
                            ),
                            position = choicePillPosition(index, windows.size),
                            dense = true,
                            modifier = Modifier.testTag("community-window-$window"),
                        )
                    }
                }
            }
        },
        modifier = modifier.padding(vertical = 6.dp),
        testTag = "community-popular",
    )
}
