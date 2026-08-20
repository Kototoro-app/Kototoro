package org.skepsun.kototoro.home.ui.compose.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.home.ui.HomeRecentItem
import org.skepsun.kototoro.home.ui.HomeRecommendationItem
import org.skepsun.kototoro.home.ui.HomeUpdateItem
import org.skepsun.kototoro.parsers.model.Content

import org.skepsun.kototoro.home.ui.compose.HOME_SECTION_GAP
import org.skepsun.kototoro.home.ui.compose.HomeBadge
import org.skepsun.kototoro.home.ui.compose.toHeroCountLabel

@Composable
internal fun HomeHighlightsSections(
    historyItems: List<HomeRecentItem>,
    recentHistoryCount: Int,
    updateItems: List<HomeUpdateItem>,
    unreadUpdatesCount: Int,
    recommendationItems: List<HomeRecommendationItem>,
    recommendationsCount: Int,
    recentSearches: List<String>,
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
    listMode: ListMode,
    onItemClick: (Content, Rect?, String?) -> Unit,
    onViewAllRecentClick: () -> Unit,
    onViewAllUpdatesClick: () -> Unit,
    onViewAllRecommendationsClick: () -> Unit,
    onRecentSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Highlight sections render as plain content in both interface styles; the legacy
    // per-style Surface container was unreachable after InterfaceStyle normalization.
    val newChaptersLabel = stringResource(R.string.new_chapters)
    val historyDisplayItems = remember(historyItems) {
        historyItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_history",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
            )
        }
    }
    val updateDisplayItems = remember(updateItems, newChaptersLabel) {
        updateItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recent_updates",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
                supportingText = if (it.newChapters > 0) {
                    HomeCoverSupportingText.Text(
                        itemNewChaptersText(newChaptersLabel, it.newChapters),
                    )
                } else {
                    null
                },
            )
        }
    }
    val recommendationDisplayItems = remember(recommendationItems) {
        recommendationItems.take(HOME_CONTENT_RAIL_PREVIEW_LIMIT).map {
            HomeCoverDisplayItem(
                content = it.content,
                sectionKey = "recommendations",
                stableKey = it.groupKey,
                counter = it.counter,
                progress = it.progress,
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalArrangement = Arrangement.spacedBy(HOME_SECTION_GAP),
    ) {
        if (historyItems.isNotEmpty()) {
            HomeHighlightSectionContainer {
                HomeContentRowSection(
                    title = stringResource(R.string.recent_history),
                    sectionKey = "recent_history",
                    iconRes = R.drawable.ic_history,
                    items = historyDisplayItems,
                    count = recentHistoryCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecentClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (updateItems.isNotEmpty()) {
            HomeHighlightSectionContainer {
                HomeContentRowSection(
                    title = stringResource(R.string.home_recent_updates),
                    sectionKey = "recent_updates",
                    iconRes = R.drawable.ic_updated,
                    items = updateDisplayItems,
                    count = unreadUpdatesCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllUpdatesClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (recommendationItems.isNotEmpty()) {
            HomeHighlightSectionContainer {
                HomeContentRowSection(
                    title = stringResource(R.string.suggestions),
                    sectionKey = "recommendations",
                    iconRes = R.drawable.ic_suggestion,
                    items = recommendationDisplayItems,
                    count = recommendationsCount,
                    posterStyle = posterStyle,
                    listMode = listMode,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecommendationsClick,
                    addTopSpacing = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (recentSearches.isNotEmpty()) {
            HomeRecentSearchSection(
                queries = recentSearches,
                onQueryClick = onRecentSearchClick,
            )
        }
    }
}

@Composable
private fun HomeHighlightSectionContainer(
    content: @Composable () -> Unit,
) {
    // Seam for a future shared highlight-section component. Both interface styles currently
    // render section content directly; the legacy per-style Surface wrapper was dead code
    // after InterfaceStyle normalization (see material3-expressive spec: hierarchy first).
    content()
}

@Composable
private fun HomeRecentSearchSection(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val chipShape = RoundedCornerShape(if (expressive) 16.dp else 8.dp)
    val chipColors = AssistChipDefaults.assistChipColors(
        containerColor = if (expressive) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        },
        labelColor = MaterialTheme.colorScheme.onSurface,
        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.home_recent_searches),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HomeBadge(
                text = queries.size.toHeroCountLabel(),
                iconRes = R.drawable.ic_history,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            queries.forEach { query ->
                AssistChip(
                    onClick = { onQueryClick(query) },
                    modifier = Modifier.height(32.dp),
                    shape = chipShape,
                    colors = chipColors,
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    label = {
                        Text(
                            text = query,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

private const val HOME_CONTENT_RAIL_PREVIEW_LIMIT = 24

