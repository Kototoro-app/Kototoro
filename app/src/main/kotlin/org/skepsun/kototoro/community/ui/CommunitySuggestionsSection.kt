package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityRecommendation
import org.skepsun.kototoro.community.domain.CommunitySuggestionsRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class CommunitySuggestionsUiState(
    val picks: List<CommunityRecommendation> = emptyList(),
    val window: String = WINDOWS.first(),
    val popular: List<CommunityChartEntry> = emptyList(),
) {
    companion object {
        val WINDOWS = listOf("week", "month", "all")
    }
}

/** The suggestions page's community rails (plan §7.3); empty, and so drawn as nothing, while the community is off. */
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

    private val picks = enabled.flatMapLatest { (on, _) ->
        if (!on) flowOf(emptyList()) else flow { emit(runCatchingCancellable { repository.recommendations() }.getOrDefault(emptyList())) }
    }.flowOn(Dispatchers.IO)

    private val popular = combine(enabled, window) { state, window -> state to window }.flatMapLatest { (state, window) ->
        if (!state.first) flowOf(emptyList()) else flow { emit(runCatchingCancellable { repository.popular(window) }.getOrDefault(emptyList())) }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<CommunitySuggestionsUiState> = combine(picks, window, popular) { picks, window, popular ->
        CommunitySuggestionsUiState(picks, window, popular)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunitySuggestionsUiState())

    fun nextWindow() {
        val windows = CommunitySuggestionsUiState.WINDOWS
        window.value = windows[(windows.indexOf(window.value) + 1) % windows.size]
    }
}

@Composable
fun CommunitySuggestionsSection(
    onSearchTitle: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val viewModel = hiltViewModel<CommunitySuggestionsViewModel>()
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val picks = state.picks.filter { !it.title.isNullOrBlank() }
    if (picks.isEmpty() && state.popular.isEmpty()) return
    Column(modifier = modifier) {
        if (picks.isNotEmpty()) {
            CommunityWorksRail(
                title = stringResource(R.string.community_for_you),
                cards = picks.map { pick ->
                    CommunityWorkCard(
                        key = pick.groupId,
                        title = pick.title.orEmpty(),
                        supportingText = pick.because.firstOrNull()?.title?.let { stringResource(R.string.community_because, it) },
                    )
                },
                onOpen = { onSearchTitle(it.title) },
                horizontalPadding = horizontalPadding,
                modifier = Modifier.padding(vertical = 6.dp),
                testTag = "community-picks",
            )
        }
        CommunityWorksRail(
            title = stringResource(R.string.community_popular),
            cards = state.popular.filter { !it.title.isNullOrBlank() }.map { entry ->
                CommunityWorkCard(
                    key = entry.id,
                    title = entry.title.orEmpty(),
                    supportingText = stringResource(R.string.community_popular_supporting, "%.1f".format(entry.score), entry.count),
                )
            },
            onOpen = { onSearchTitle(it.title) },
            horizontalPadding = horizontalPadding,
            titleTrailing = {
                TextButton(onClick = viewModel::nextWindow) {
                    Text(
                        stringResource(
                            when (state.window) {
                                "month" -> R.string.community_window_month
                                "all" -> R.string.community_window_all
                                else -> R.string.community_window_week
                            },
                        ),
                    )
                }
            },
            modifier = Modifier.padding(vertical = 6.dp),
            testTag = "community-popular",
        )
    }
}
