package org.skepsun.kototoro.explore.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.TVBoxRepositorySelection
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.ContentSourceResolvedIcon
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.discover.ui.compose.DiscoverHeroCarousel
import org.skepsun.kototoro.discover.ui.compose.discoverHeroHeight
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.util.Locale

@Composable
internal fun BrowseHeroBlock(
    title: String,
    heroItems: List<ContentListModel>,
    activeService: ScrobblerService?,
    availableServices: List<ScrobblerService>,
    isLoadingOnly: Boolean,
    topContentInset: androidx.compose.ui.unit.Dp,
    settings: AppSettings,
    onSelectService: (ScrobblerService) -> Unit,
    onOpenSchedule: (() -> Unit)? = null,
    onHeroItemClick: (ContentListModel, String) -> Unit,
    sharedElementKeyForItem: (ContentListModel, Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (heroItems.isNotEmpty()) {
        DiscoverHeroCarousel(
            title = title,
            items = heroItems,
            activeService = activeService,
            availableServices = availableServices,
            onSelectService = onSelectService,
            onOpenSchedule = onOpenSchedule,
            onItemClick = { item, _, sharedElementKey -> onHeroItemClick(item, sharedElementKey) },
            topContentInset = topContentInset,
            detachedBottomContent = true,
            settings = settings,
            sharedElementKeyForItem = sharedElementKeyForItem,
            modifier = modifier,
        )
    } else {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(
                    topContentInset + discoverHeroHeight(
                        isLandscape = isLandscape,
                        detachedBottomContent = true,
                    ),
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(top = topContentInset + 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoadingOnly) {
                BrowseHeroSkeleton()
            }
        }
    }
}

@Composable
private fun SourcesQuickAccessSection(
    sources: List<ContentSourceItem>,
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    isGroupedByLanguage: Boolean,
    selectedSourceIds: Set<Long>,
    forceExpanded: Boolean = false,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_extension),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.explore_tab_sources),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = onManageClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.extension_management),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            var isExpanded by rememberSaveable(sources.size) { mutableStateOf(false) }
            val columns = remember(maxWidth, metrics, browseListMode) {
                calculateSourceGridColumns(
                    availableWidth = maxWidth,
                    metrics = metrics,
                    browseListMode = browseListMode,
                )
            }
            val collapsedRowCount = if (maxWidth < 520.dp) 5 else 4
            val collapsedVisibleCount = columns * collapsedRowCount
            val groupedSources = remember(sources, isGroupedByLanguage, context) {
                sources.toQuickAccessGroups(
                    isGroupedByLanguage = isGroupedByLanguage,
                    context = context,
                )
            }
            val effectiveExpanded = forceExpanded || isExpanded
            val visibleGroups = remember(groupedSources, collapsedVisibleCount, effectiveExpanded) {
                groupedSources.takeVisibleSourceGroups(
                    maxSources = if (effectiveExpanded) Int.MAX_VALUE else collapsedVisibleCount,
                )
            }
            val hasMoreSources = !forceExpanded && sources.size > collapsedVisibleCount

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleGroups.forEach { group ->
                    group.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 2.dp),
                        )
                    }
                    SourceQuickAccessGrid(
                        metrics = metrics,
                        browseListMode = browseListMode,
                        columns = columns,
                        sources = group.sources,
                        selectedSourceIds = selectedSourceIds,
                        onSourceClick = onSourceClick,
                        onSourceLongClick = onSourceLongClick,
                    )
                }
                if (hasMoreSources) {
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = if (effectiveExpanded) {
                                stringResource(R.string.show_less)
                            } else {
                                "${stringResource(R.string.show_more)} (${sources.size - collapsedVisibleCount})"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceQuickAccessGrid(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    sources: List<ContentSourceItem>,
    selectedSourceIds: Set<Long>,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
) {
    val rows = remember(sources, columns) { sources.chunked(columns) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        rows.forEach { rowSources ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
            ) {
                rowSources.forEach { source ->
                    Box(modifier = Modifier.weight(1f)) {
                        SourceQuickAccessCard(
                            metrics = metrics,
                            browseListMode = browseListMode,
                            source = source,
                            isSelected = source.id in selectedSourceIds,
                            onClick = { onSourceClick(source) },
                            onLongClick = { onSourceLongClick(source) },
                        )
                    }
                }
                repeat(columns - rowSources.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun LazyListScope.sourceQuickAccessItems(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    visibleGroups: List<SourceQuickAccessRows>,
    selectedSourceIds: Set<Long>,
    startPadding: androidx.compose.ui.unit.Dp,
    endPadding: androidx.compose.ui.unit.Dp,
    hasMoreSources: Boolean,
    isExpanded: Boolean,
    topBackgroundOverlap: androidx.compose.ui.unit.Dp,
    tvBoxRepositorySelection: TVBoxRepositorySelection,
    onTvBoxRepositorySelected: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onManageClick: () -> Unit,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
) {
    item(key = "source_quick_access_header", contentType = "source_quick_access_header") {
        SourceQuickAccessHeader(
            tvBoxRepositorySelection = tvBoxRepositorySelection,
            onTvBoxRepositorySelected = onTvBoxRepositorySelected,
            onManageClick = onManageClick,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    start = startPadding,
                    end = endPadding,
                    top = topBackgroundOverlap,
                    bottom = 4.dp,
                ),
        )
    }
    visibleGroups.forEachIndexed { groupIndex, group ->
        group.title?.let { title ->
            item(
                key = "source_group_${groupIndex}_$title",
                contentType = "source_group_header",
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            start = startPadding,
                            end = endPadding,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                )
            }
        }
        val rows = group.rows
        itemsIndexed(
            items = rows,
            key = { rowIndex, rowSources ->
                val firstId = rowSources.firstOrNull()?.id ?: rowIndex.toLong()
                "source_row_${groupIndex}_${rowIndex}_$firstId"
            },
            contentType = { _, _ -> "source_row" },
        ) { rowIndex, rowSources ->
            SourceQuickAccessRow(
                metrics = metrics,
                browseListMode = browseListMode,
                columns = columns,
                sources = rowSources,
                selectedSourceIds = selectedSourceIds,
                onSourceClick = onSourceClick,
                onSourceLongClick = onSourceLongClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(
                        start = startPadding,
                        end = endPadding,
                        bottom = if (rowIndex == rows.lastIndex) 0.dp else metrics.gridSpacing,
                    ),
            )
        }
    }
    if (hasMoreSources) {
        item(key = "source_quick_access_more", contentType = "source_quick_access_more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onToggleExpanded,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (isExpanded) {
                            stringResource(R.string.show_less)
                        } else {
                            stringResource(R.string.show_more)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceQuickAccessHeader(
    tvBoxRepositorySelection: TVBoxRepositorySelection,
    onTvBoxRepositorySelected: (String) -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.explore_tab_sources),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tvBoxRepositorySelection.options.isNotEmpty()) {
                TVBoxRepositorySelector(
                    selection = tvBoxRepositorySelection,
                    onRepositorySelected = onTvBoxRepositorySelected,
                )
            }
            TextButton(
                onClick = onManageClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.extension_management),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TVBoxRepositorySelector(
    selection: TVBoxRepositorySelection,
    onRepositorySelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(selection.activeId) { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = 200.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.tvbox_repository_selector,
                    selection.active?.title.orEmpty(),
                ),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            selection.options.forEach { repository ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = repository.title,
                            fontWeight = if (repository.id == selection.activeId) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onRepositorySelected(repository.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceQuickAccessRow(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    columns: Int,
    sources: List<ContentSourceItem>,
    selectedSourceIds: Set<Long>,
    onSourceClick: (ContentSourceItem) -> Unit,
    onSourceLongClick: (ContentSourceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
    ) {
        sources.forEach { source ->
            Box(modifier = Modifier.weight(1f)) {
                SourceQuickAccessCard(
                    metrics = metrics,
                    browseListMode = browseListMode,
                    source = source,
                    isSelected = source.id in selectedSourceIds,
                    onClick = { onSourceClick(source) },
                    onLongClick = { onSourceLongClick(source) },
                )
            }
        }
        repeat(columns - sources.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceQuickAccessCard(
    metrics: SourceQuickAccessMetrics,
    browseListMode: ListMode,
    source: ContentSourceItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val actualSource = source.source.mangaSource
    val title = actualSource.getTitle(context)
    val isGridCard = browseListMode == ListMode.GRID || browseListMode == ListMode.COMPACT_GRID
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val cardShape = RoundedCornerShape(
        when {
            expressive && isGridCard -> 20.dp
            expressive -> 18.dp
            isGridCard -> 14.dp
            else -> 12.dp
        },
    )
    val cardBackground = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.background
    }
    val iconShape = RoundedCornerShape(if (expressive) 14.dp else if (isGridCard) 14.dp else 12.dp)
    val iconBackground = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (expressive) 0.62f else if (isGridCard) 0.44f else 0.52f,
    )

    if (isGridCard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(cardShape)
                .background(cardBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.iconContainerSize)
                    .clip(iconShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                ContentSourceResolvedIcon(
                    source = actualSource,
                    modifier = Modifier.size(metrics.iconSize),
                    styleResId = R.style.FaviconDrawable_SourceIcon,
                    throttleNetworkLoad = true,
                    contentDescription = title,
                )
                SourceAvailabilityBadge(
                    availability = source.source.availability,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                if (source.source.isPinned) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        tonalElevation = 1.dp,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin_small),
                            contentDescription = stringResource(R.string.source_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(10.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = metrics.titleTextSize,
                    lineHeight = (metrics.titleTextSize.value + 2f).sp,
                ),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(cardShape)
                .background(cardBackground)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(iconShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                ContentSourceResolvedIcon(
                    source = actualSource,
                    modifier = Modifier.size(28.dp),
                    styleResId = R.style.FaviconDrawable_SourceIcon,
                    throttleNetworkLoad = true,
                    contentDescription = title,
                )
                SourceAvailabilityBadge(
                    availability = source.source.availability,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                if (source.source.isPinned) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        tonalElevation = 1.dp,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin_small),
                            contentDescription = stringResource(R.string.source_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(2.dp)
                                .size(9.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (browseListMode == ListMode.DETAILED_LIST) 2.dp else 0.dp),
            ) {
                Text(
                    text = title,
                    style = if (browseListMode == ListMode.DETAILED_LIST) {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (browseListMode == ListMode.DETAILED_LIST) {
                    Text(
                        text = actualSource.getLocale()?.getDisplayName(Locale.getDefault()).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceAvailabilityBadge(
    availability: ContentSourceAvailability,
    modifier: Modifier = Modifier,
) {
    if (availability != ContentSourceAvailability.EMPTY) {
        return
    }
    Surface(
        modifier = modifier.padding(3.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.error,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = stringResource(R.string.source_empty_badge_short),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

