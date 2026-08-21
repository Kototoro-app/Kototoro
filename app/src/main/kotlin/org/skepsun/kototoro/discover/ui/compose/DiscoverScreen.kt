package org.skepsun.kototoro.discover.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.discover.ui.model.DiscoverCarouselRow
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.compose.KototoroContentCard
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory

private data class DiscoverPreparedItems(
    val carouselRows: List<DiscoverCarouselRow>,
    val gridItems: List<ContentListModel>,
    val emptyState: EmptyState?,
    val heroRow: DiscoverCarouselRow?,
    val heroItems: List<ContentListModel>,
)

@Immutable
private data class DiscoverScreenPrefs(
    val gridScale: Float,
    val cardUiPrefs: ContentCardUiPrefs,
)

private fun prepareDiscoverItems(items: List<ListModel>): DiscoverPreparedItems {
    val carouselRows = ArrayList<DiscoverCarouselRow>()
    val gridItems = ArrayList<ContentListModel>()
    var emptyState: EmptyState? = null
    var heroRow: DiscoverCarouselRow? = null
    var heroItems: List<ContentListModel> = emptyList()

    items.forEach { item ->
        when (item) {
            is DiscoverCarouselRow -> {
                carouselRows += item
                if (heroRow == null) {
                    val rowHeroItems = item.items
                        .asSequence()
                        .filterIsInstance<ContentListModel>()
                        .take(6)
                        .toList()
                    if (rowHeroItems.isNotEmpty()) {
                        heroRow = item
                        heroItems = rowHeroItems
                    }
                }
            }
            is ContentListModel -> gridItems += item
            is EmptyState -> if (emptyState == null) {
                emptyState = item
            }
        }
    }

    return DiscoverPreparedItems(
        carouselRows = carouselRows,
        gridItems = gridItems,
        emptyState = emptyState,
        heroRow = heroRow,
        heroItems = heroItems,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    items: List<ListModel>,
    isRefreshing: Boolean,
    isCarousel: Boolean,
    isLoadingOnly: Boolean,
    activeService: ScrobblerService? = null,
    availableServices: List<ScrobblerService> = emptyList(),
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (ContentListModel, Rect?, String?) -> Unit,
    onSelectService: (ScrobblerService) -> Unit = {},
    onOpenSchedule: (() -> Unit)? = null,
    onCategoryMoreClick: (TrackingSiteCategory) -> Unit,
    gridSpanCount: Int,
    cardUiPrefsOverride: ContentCardUiPrefs? = null,
    modifier: Modifier = Modifier
) {
    if (isLoadingOnly) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KototoroLoadingIndicator()
        }
        return
    }
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_BADGES_TOP_LEFT,
        AppSettings.KEY_BADGES_TOP_RIGHT,
        AppSettings.KEY_BADGES_BOTTOM_LEFT,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
            AppSettings.KEY_SHOW_EXTRA_INFO_ON_CARDS,
    ) {
        DiscoverScreenPrefs(
            gridScale = gridSize / 100f,
            cardUiPrefs = ContentCardUiPrefs(
                badgesTopLeft = badgesTopLeft,
                badgesTopRight = badgesTopRight,
                badgesBottomLeft = badgesBottomLeft,
                    showExtraInfo = showExtraInfoOnCards,
                badgesBottomRight = badgesBottomRight,
            ),
        )
    }
    val gridScale = screenPrefs.gridScale
    val cardUiPrefs = cardUiPrefsOverride ?: screenPrefs.cardUiPrefs

    KototoroPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        indicatorTopInset = contentPadding,
    ) {
        val preparedItems = remember(items) { prepareDiscoverItems(items) }
        val carouselRows = preparedItems.carouselRows
        val emptyState = preparedItems.emptyState

        if (isCarousel) {
            val heroRow = preparedItems.heroRow
            val heroItems = preparedItems.heroItems

            if (carouselRows.isEmpty() && emptyState != null) {
                DiscoverEmptyState(
                    state = emptyState,
                    contentPadding = contentPadding,
                    activeService = activeService,
                    availableServices = availableServices,
                    onSelectService = onSelectService,
                )
                return@KototoroPullToRefreshBox
            }

            val listState = rememberSaveable(saver = LazyListState.Saver) {
                LazyListState()
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 8.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                if (heroItems.isNotEmpty() && heroRow != null) {
                    item(key = "discover_hero") {
                        DiscoverHeroCarousel(
                            title = stringResource(heroRow.category.nameResId),
                            items = heroItems,
                            activeService = activeService,
                            availableServices = availableServices,
                            onItemClick = { item, coverBounds, sharedElementKey ->
                                onItemClick(item, coverBounds, sharedElementKey)
                            },
                            onSelectService = onSelectService,
                            onOpenSchedule = onOpenSchedule,
                            settings = settings,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                items(
                    items = carouselRows,
                    key = { it.category.id },
                    contentType = { _ -> "discover_carousel" },
                ) { row ->
                    DiscoverCarousel(
                        row = row,
                        gridScale = gridScale,
                        badgesBottomRight = cardUiPrefs.badgesBottomRight,
                        onItemClick = onItemClick,
                        onMoreClick = onCategoryMoreClick
                    )
                }
            }
        } else {
            val gridState = rememberSaveable(saver = LazyGridState.Saver) {
                LazyGridState()
            }
            val gridItems = preparedItems.gridItems

            // Trigger pagination threshold for grid
            val shouldLoadMore by remember(gridState, gridSpanCount, gridItems.size) {
                derivedStateOf {
                    if (gridItems.isEmpty()) {
                        return@derivedStateOf false
                    }
                    val layoutInfo = gridState.layoutInfo
                    val totalVisibleItems = layoutInfo.visibleItemsInfo.size
                    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisibleItemIndex >= layoutInfo.totalItemsCount - 4 * gridSpanCount && totalVisibleItems > 0
                }
            }

            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore && !isRefreshing) {
                    onLoadMore()
                }
            }

            if (gridItems.isEmpty() && emptyState != null) {
                DiscoverEmptyState(
                    state = emptyState,
                    contentPadding = contentPadding,
                    activeService = activeService,
                    availableServices = availableServices,
                    onSelectService = onSelectService,
                )
                return@KototoroPullToRefreshBox
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(gridSpanCount),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentPadding.calculateTopPadding() + 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = gridItems,
                    key = { _, item -> "${item.manga.source.name}_${item.manga.id}" },
                    contentType = { _, _ -> "discover_grid_card" },
                ) { _, item ->
                    val sharedElementKey = remember(item.manga.source.name, item.coverUrl) {
                        contentCoverSharedKey(item.manga.source.name, item.coverUrl.orEmpty())
                    }
                    KototoroContentCard(
                        model = item,
                        isListLayout = false,
                        onClick = { coverBounds -> onItemClick(item, coverBounds, sharedElementKey) },
                        onLongClick = { },
                        isSelected = false,
                        selectionModeActive = false,
                        uiPrefs = cardUiPrefs,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverEmptyState(
    state: EmptyState,
    contentPadding: PaddingValues,
    activeService: ScrobblerService? = null,
    availableServices: List<ScrobblerService> = emptyList(),
    onSelectService: (ScrobblerService) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 24.dp,
                end = 24.dp,
                top = contentPadding.calculateTopPadding() + 24.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val primaryText = state.textPrimaryText?.toString()
                ?: if (state.textPrimary != 0) stringResource(state.textPrimary) else ""
            val secondaryText = state.textSecondaryText?.toString()
                ?: if (state.textSecondary != 0) stringResource(state.textSecondary) else ""
            Image(
                painter = painterResource(state.icon),
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            androidx.compose.material3.Text(
                text = primaryText,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            androidx.compose.material3.Text(
                text = secondaryText,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (activeService != null && availableServices.size > 1) {
                DiscoverServiceSwitcherChip(
                    activeService = activeService,
                    availableServices = availableServices,
                    onSelectService = onSelectService,
                )
            }
        }
    }
}

@Composable
private fun DiscoverServiceSwitcherChip(
    activeService: ScrobblerService,
    availableServices: List<ScrobblerService>,
    onSelectService: (ScrobblerService) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.AssistChip(
            onClick = { expanded = true },
            leadingIcon = {
                androidx.compose.material3.Icon(
                    painter = rememberSafePainter(activeService.iconResId),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 2.dp),
                )
            },
            trailingIcon = {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                )
            },
            label = { androidx.compose.material3.Text(stringResource(activeService.titleResId)) },
        )
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
        ) {
            availableServices.forEach { candidate ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(candidate.titleResId)) },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            painter = rememberSafePainter(candidate.iconResId),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectService(candidate)
                    },
                )
            }
        }
    }
}
