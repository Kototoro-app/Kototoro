package org.skepsun.kototoro.community.ui

import androidx.compose.runtime.Composable
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
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunitySuggestionsRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

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

/** The suggestions page's community chart, drawn by the shared [CommunityChartSection]. */
@Composable
fun CommunitySuggestionsSection(
    onSearchTitle: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val viewModel = hiltViewModel<CommunitySuggestionsViewModel>()
    val state = viewModel.state.collectAsStateWithLifecycle().value
    CommunityChartSection(state, viewModel::selectWindow, onSearchTitle, modifier, horizontalPadding)
}
