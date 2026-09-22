package org.skepsun.kototoro.home.ui.compose.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
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
    historyStyle: HomeRailStyle,
    updatesStyle: HomeRailStyle,
    recommendationsStyle: HomeRailStyle,
    onItemClick: (Content, Rect?, String?) -> Unit,
    onViewAllRecentClick: () -> Unit,
    onViewAllUpdatesClick: () -> Unit,
    onViewAllRecommendationsClick: () -> Unit,
    onConfigureHistoryClick: () -> Unit,
    onConfigureUpdatesClick: () -> Unit,
    onConfigureRecommendationsClick: () -> Unit,
    onRecentSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep each rail's list/grid mode and sizing independent; the wrapper only
    // supplies the shared artwork grouping surface and its inner content inset.
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop
    val usesSectionContainer = usesArtworkBackdrop || LocalInterfaceStyle.current != InterfaceStyle.IOS
    val sectionContentPadding = if (usesSectionContainer) {
        AppLayoutTokens.sectionHorizontalPadding
    } else {
        0.dp
    }
    val sectionContentModifier = if (usesSectionContainer) {
        Modifier
    } else {
        Modifier.padding(vertical = 2.dp)
    }
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
                    items = historyDisplayItems,
                    count = recentHistoryCount,
                    railStyle = historyStyle,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecentClick,
                    onConfigureClick = onConfigureHistoryClick,
                    addTopSpacing = false,
                    contentHorizontalPadding = sectionContentPadding,
                    modifier = sectionContentModifier,
                )
            }
        }
        if (updateItems.isNotEmpty()) {
            HomeHighlightSectionContainer {
                HomeContentRowSection(
                    title = stringResource(R.string.home_recent_updates),
                    sectionKey = "recent_updates",
                    items = updateDisplayItems,
                    count = unreadUpdatesCount,
                    railStyle = updatesStyle,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllUpdatesClick,
                    onConfigureClick = onConfigureUpdatesClick,
                    addTopSpacing = false,
                    contentHorizontalPadding = sectionContentPadding,
                    modifier = sectionContentModifier,
                )
            }
        }
        if (recommendationItems.isNotEmpty()) {
            HomeHighlightSectionContainer {
                HomeContentRowSection(
                    title = stringResource(R.string.suggestions),
                    sectionKey = "recommendations",
                    items = recommendationDisplayItems,
                    count = recommendationsCount,
                    railStyle = recommendationsStyle,
                    onItemClick = onItemClick,
                    onMoreClick = onViewAllRecommendationsClick,
                    onConfigureClick = onConfigureRecommendationsClick,
                    addTopSpacing = false,
                    contentHorizontalPadding = sectionContentPadding,
                    modifier = sectionContentModifier,
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
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop
    val sectionShape = RoundedCornerShape(LocalInterfaceStyleTokens.current.sectionCornerRadius)
    if (!usesArtworkBackdrop) {
        if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
            // iOS keeps the plain home hierarchy when there is no artwork to
            // sample; the glass surface has no visual source in this state.
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        } else {
            // Material 3 keeps an opaque tonal container on a plain background
            // so the section remains distinguishable from the page canvas.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = sectionShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
                content = content,
            )
        }
        return
    }

    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = sectionShape,
            style = GlassDefaults.subtleStyle(),
            componentRole = GlassComponentRole.ContentOverlay,
            highlightOnIdle = false,
            lensEnabled = false,
            pressFeedbackEnabled = false,
            content = { content() },
        )
    } else {
        // MD3 keeps a conventional tonal container and never reaches the
        // vendor backdrop effects. The surface is deliberately translucent
        // (0.3) so the artwork stays visible through the tray; the Material 3
        // tonal family still separates the section from the page canvas.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = sectionShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f),
            tonalElevation = 0.dp,
            content = content,
        )
    }
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
    // The section title sits directly on the page canvas (outside the trays);
    // over the artwork backdrop it gets the same lightweight pill as the
    // group headers so it stays readable on any image.
    val usesArtworkBackdrop = LocalBackgroundStyle.current.usesArtworkBackdrop
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
            if (usesArtworkBackdrop) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.home_recent_searches),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.home_recent_searches),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
