package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.core.ui.chips.CompactChoicePill
import org.skepsun.kototoro.core.ui.chips.choicePillPosition
import org.skepsun.kototoro.core.ui.text.uiString

data class CommunitySuggestionsUiState(
    /** False until the index answered, or while the community is off. */
    val isLoaded: Boolean = false,
    val window: String = WINDOWS.first(),
    val entries: List<CommunityChartEntry> = emptyList(),
    /** No chart has entries in any window yet, so [entries] lists every rated work rather than a ranking. */
    val isFallback: Boolean = false,
    /** Cover models by work id, for the works a source of the reader's was found for; filled in after the chart. */
    val covers: Map<String, Any> = emptyMap(),
) {
    companion object {
        val WINDOWS = listOf("week", "month", CommunityHighlights.WINDOW_ALL)
    }
}

/**
 * The community chart on the suggestions page. While the index is too small for any chart, it lists every work readers
 * rated instead, so the community is visible from its first ratings. Draws nothing when there is nothing to list.
 */
@Composable
fun CommunityChartSection(
    state: CommunitySuggestionsUiState,
    onSelectWindow: (String) -> Unit,
    onOpenWork: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    /** A long press on a work: where else it can be looked for. */
    onShowSources: ((id: String, title: String) -> Unit)? = null,
    /** Draws a work's cover model from [CommunitySuggestionsUiState.covers] as its card background. */
    coverContent: (@Composable (cover: Any, modifier: Modifier) -> Unit)? = null,
) {
    if (!state.isLoaded || (state.isFallback && state.entries.isEmpty())) return
    CommunityWorksRail(
        title = uiString(if (state.isFallback) "community_rated" else "community_popular"),
        cards = state.entries.map { entry ->
            CommunityWorkCard(
                key = entry.id,
                title = entry.title.orEmpty(),
                supportingText = uiString("community_rating_count", entry.count),
                score = entry.displayScore,
                cover = state.covers[entry.id],
            )
        },
        onOpen = { onOpenWork(it.key, it.title) },
        onLongOpen = onShowSources?.let { show -> { show(it.key, it.title) } },
        horizontalPadding = horizontalPadding,
        ranked = !state.isFallback,
        note = if (state.isFallback) uiString("community_rated_note") else null,
        emptyText = uiString("community_chart_empty"),
        titleTrailing = if (state.isFallback) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val windows = CommunitySuggestionsUiState.WINDOWS
                    windows.forEachIndexed { index, window ->
                        CompactChoicePill(
                            selected = state.window == window,
                            onClick = { onSelectWindow(window) },
                            label = uiString(
                                when (window) {
                                    "month" -> "community_window_month"
                                    CommunityHighlights.WINDOW_ALL -> "community_window_all"
                                    else -> "community_window_week"
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
        coverContent = coverContent,
    )
}
