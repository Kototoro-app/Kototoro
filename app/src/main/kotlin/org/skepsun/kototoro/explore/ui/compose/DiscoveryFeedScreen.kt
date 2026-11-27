package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.buttons.FilledTonalButton
import org.skepsun.kototoro.core.ui.buttons.IconButton
import org.skepsun.kototoro.core.ui.buttons.OutlinedButton
import org.skepsun.kototoro.core.ui.buttons.TextButton
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.EmptyStatePanel
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.ui.DiscoveryFeedRowState
import org.skepsun.kototoro.explore.ui.DiscoveryFeedUiState
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.compose.KototoroContentCardGrid
import org.skepsun.kototoro.list.ui.compose.contentListSharedElementKey
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

internal const val DISCOVERY_FEED_ROW_KEY_PREFIX = "discovery_feed_row:"

internal fun suggestDiscoveryFeedDisplayName(
    sourceTitle: String,
    savedFilterName: String?,
    query: String?,
): String {
    val cleanSource = sourceTitle.trim()
    val suffix = savedFilterName?.trim()?.takeIf { it.isNotEmpty() }
        ?: query?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanSource.isNotEmpty() && suffix != null -> "$cleanSource · $suffix"
        cleanSource.isNotEmpty() -> cleanSource
        else -> suffix.orEmpty()
    }
}

internal fun buildDiscoveryFeedRowSubtitle(
    sourceTitle: String,
    row: DiscoveryFeedRow,
    savedFilterBadge: String,
): String? {
    val parts = buildList {
        val cleanSource = sourceTitle.trim()
        if (cleanSource.isNotEmpty() && !cleanSource.equals(row.displayName.trim(), ignoreCase = true)) {
            add(cleanSource)
        }
        val query = row.normalizedQuery
        when {
            row.savedFilterId != null && query != null -> add("$savedFilterBadge · \"$query\"")
            row.savedFilterId != null -> add(savedFilterBadge)
            query != null -> add("\"$query\"")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = " · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryFeedPage(
    uiState: DiscoveryFeedUiState,
    /** False while the page is only peeking in during a swipe: rows load once it settles on screen. */
    isActive: Boolean = true,
    sourcesByName: Map<String, ContentSource>,
    contentPadding: PaddingValues,
    posterStyle: CompactPosterCardStyle,
    gridScale: Float,
    cardUiPrefs: ContentCardUiPrefs,
    onRowVisible: (String) -> Unit,
    onRetryRow: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onContentClick: (Content, String?) -> Unit,
    onRowHeaderClick: (DiscoveryFeedRow) -> Unit,
    onEditRowClick: (DiscoveryFeedRow) -> Unit,
    onAddRowClick: () -> Unit,
    onManageRowsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    ScrollToTopEffect {
        listState.scrollToItem(0)
    }

    val visibleRowIds by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val key = info.key as? String ?: return@mapNotNull null
                if (key.startsWith(DISCOVERY_FEED_ROW_KEY_PREFIX)) {
                    key.removePrefix(DISCOVERY_FEED_ROW_KEY_PREFIX)
                } else {
                    null
                }
            }
        }
    }

    // Trigger lazy loading when rows scroll into viewport.
    LaunchedEffect(listState, isActive) {
        if (!isActive) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info ->
                    val key = info.key as? String ?: return@mapNotNull null
                    if (key.startsWith(DISCOVERY_FEED_ROW_KEY_PREFIX)) {
                        key.removePrefix(DISCOVERY_FEED_ROW_KEY_PREFIX)
                    } else {
                        null
                    }
                }
                .toSet()
        }
            .distinctUntilChanged()
            .collect { ids ->
                ids.forEach(onRowVisible)
            }
    }

    // Also trigger loads for visible rows whose state reset back to Idle (e.g. returning to Discovery
    // after cancellation or newly added rows entering the initial viewport).
    LaunchedEffect(isActive, visibleRowIds, uiState.visibleRows, uiState.rowStates) {
        if (!isActive) return@LaunchedEffect
        visibleRowIds.forEach { rowId ->
            if (uiState.stateOf(rowId) is DiscoveryFeedRowState.Idle) {
                onRowVisible(rowId)
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val horizontalStart = contentPadding.calculateStartPadding(layoutDirection)
    val horizontalEnd = contentPadding.calculateEndPadding(layoutDirection)

    KototoroPullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefreshAll,
        modifier = modifier.fillMaxSize(),
        indicatorTopInset = contentPadding,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (uiState.visibleRows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = horizontalStart,
                            end = horizontalEnd,
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + 72.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.allRows.isEmpty()) {
                        EmptyStatePanel(
                            icon = Icons.Outlined.Explore,
                            title = stringResource(R.string.discovery_feed_empty_title),
                            message = stringResource(R.string.discovery_feed_empty_message),
                            actionLabel = stringResource(R.string.discovery_feed_add_first_row),
                            onAction = onAddRowClick,
                        )
                    } else {
                        EmptyStatePanel(
                            icon = Icons.Outlined.VisibilityOff,
                            title = stringResource(R.string.discovery_feed_all_hidden_title),
                            message = stringResource(R.string.discovery_feed_all_hidden_message),
                            actionLabel = stringResource(R.string.discovery_feed_manage_rows),
                            onAction = onManageRowsClick,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = horizontalStart,
                        end = horizontalEnd,
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 104.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = uiState.visibleRows,
                        key = { "$DISCOVERY_FEED_ROW_KEY_PREFIX${it.id}" },
                        contentType = { "discovery_feed_row" },
                    ) { row ->
                        DiscoveryFeedRowSection(
                            row = row,
                            source = sourcesByName[row.sourceName],
                            rowState = uiState.stateOf(row.id),
                            cardModels = uiState.cardModelsOf(row.id),
                            posterStyle = posterStyle,
                            gridScale = gridScale,
                            cardUiPrefs = cardUiPrefs,
                            onContentClick = onContentClick,
                            onRetry = { onRetryRow(row.id) },
                            onHeaderClick = { onRowHeaderClick(row) },
                            onEditClick = { onEditRowClick(row) },
                        )
                    }

                    item(key = "discovery_feed_footer", contentType = "discovery_feed_footer") {
                        DiscoveryFeedFooterActions(
                            onAddRowClick = onAddRowClick,
                            onManageRowsClick = onManageRowsClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CompactTopBarHorizontalPadding, vertical = 4.dp),
                        )
                    }
                }

                VerticalScrollbar(
                    state = listState,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
internal fun DiscoveryFeedRowSection(
    row: DiscoveryFeedRow,
    source: ContentSource?,
    rowState: DiscoveryFeedRowState,
    cardModels: List<ContentGridModel>,
    posterStyle: CompactPosterCardStyle,
    gridScale: Float,
    cardUiPrefs: ContentCardUiPrefs,
    onContentClick: (Content, String?) -> Unit,
    onRetry: () -> Unit,
    onHeaderClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sourceTitle = remember(source, row.sourceName, context) {
        source?.getTitle(context) ?: row.sourceName
    }
    val savedFilterBadge = stringResource(R.string.discovery_feed_saved_filter_badge)
    val subtitle = remember(sourceTitle, row, savedFilterBadge) {
        buildDiscoveryFeedRowSubtitle(
            sourceTitle = sourceTitle,
            row = row,
            savedFilterBadge = savedFilterBadge,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompactTopBarHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = source != null, onClick = onHeaderClick)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (source != null) {
                    if (LocalInspectionMode.current) {
                        Surface(
                            modifier = Modifier.size(20.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = sourceTitle.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    } else {
                        ContentSourceIcon(
                            source = source,
                            modifier = Modifier.size(20.dp),
                            contentDescription = null,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (rowState is DiscoveryFeedRowState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }

            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (source != null) {
                TextButton(
                    onClick = onHeaderClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false),
                ) {
                    Text(
                        text = stringResource(R.string.more),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        when (rowState) {
            DiscoveryFeedRowState.Idle,
            DiscoveryFeedRowState.Loading -> {
                DiscoveryFeedRowLoadingPlaceholder(
                    posterStyle = posterStyle,
                )
            }

            is DiscoveryFeedRowState.Error -> {
                DiscoveryFeedRowErrorCard(
                    message = rowState.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.discovery_feed_row_load_failed),
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CompactTopBarHorizontalPadding),
                )
            }

            is DiscoveryFeedRowState.Loaded -> {
                if (cardModels.isEmpty()) {
                    DiscoveryFeedRowEmptyCard(
                        onRetry = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CompactTopBarHorizontalPadding),
                    )
                } else {
                    DiscoveryFeedLoadedRowCarousel(
                        rowId = row.id,
                        items = cardModels,
                        posterStyle = posterStyle,
                        gridScale = gridScale,
                        cardUiPrefs = cardUiPrefs,
                        onContentClick = onContentClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryFeedLoadedRowCarousel(
    rowId: String,
    items: List<ContentGridModel>,
    posterStyle: CompactPosterCardStyle,
    gridScale: Float,
    cardUiPrefs: ContentCardUiPrefs,
    onContentClick: (Content, String?) -> Unit,
) {
    val rowListState = rememberSaveable(rowId, saver = LazyListState.Saver) {
        LazyListState()
    }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv

    LazyRow(
        state = rowListState,
        modifier = Modifier
            .fillMaxWidth()
            .keepHorizontalScrollInside()
            .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = items,
            key = { item -> "${rowId}_${item.id}" },
            contentType = { "discovery_feed_card" },
        ) { model ->
            val instanceKey = "discovery_feed_${rowId}_${model.id}"
            val sharedKey = remember(model.id, instanceKey) {
                contentListSharedElementKey(model, instanceKey)
            }
            KototoroContentCardGrid(
                item = model,
                gridScale = gridScale,
                cardStyle = posterStyle,
                cellContentPadding = PaddingValues(0.dp),
                uiPrefs = cardUiPrefs,
                sharedElementInstanceKey = instanceKey,
                onClick = { _ -> onContentClick(model.manga, sharedKey) },
                onLongClick = {},
                modifier = Modifier.width(posterStyle.itemWidth),
            )
        }
    }
}

@Composable
internal fun DiscoveryFeedRowLoadingPlaceholder(
    posterStyle: CompactPosterCardStyle,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "discovery_feed_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "discovery_feed_shimmer_alpha",
    )
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha)

    LazyRow(
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(count = 4, key = { "placeholder_$it" }) {
            Column(
                modifier = Modifier.width(posterStyle.itemWidth),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(posterStyle.posterHeight)
                        .clip(RoundedCornerShape(posterStyle.cornerRadius))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.56f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(placeholderColor),
                )
            }
        }
    }
}

@Composable
internal fun DiscoveryFeedRowErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.discovery_feed_row_load_failed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun DiscoveryFeedRowEmptyCard(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.discovery_feed_row_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun DiscoveryFeedFooterActions(
    onAddRowClick: () -> Unit,
    onManageRowsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onAddRowClick) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.discovery_feed_add_row))
        }
        OutlinedButton(onClick = onManageRowsClick) {
            Icon(
                painter = painterResource(R.drawable.ic_reorder_handle),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.discovery_feed_manage_rows))
        }
    }
}
