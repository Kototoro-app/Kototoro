package org.skepsun.kototoro.core.ui.topbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import kotlin.math.floor

/*
 * The platform-independent parts of Android's compact top bar: title, search pill content and the category
 * and filter rails. Each host supplies the capsule behind a rail ([RailSurface]) — glass or a plain surface.
 */

/** Draws the capsule behind a rail; `modifier` carries the capsule's size and alignment. */
typealias RailSurface = @Composable BoxScope.(modifier: Modifier) -> Unit

val TopBarTabsRailVisualHeight = 40.dp
val TopBarFilterRailVisualHeight = 36.dp
private val RailEdgeFadeExtent = 16.dp

@Immutable
data class TopBarTabItem(val id: Long, val title: String)

/**
 * Softens the start/end edges of a scrolling compact rail so items cut off by the capsule border dissolve
 * instead of colliding with it. Each edge only fades while the rail can still scroll that way.
 */
fun Modifier.compactRailEdgeFade(
    fadeStart: Boolean,
    fadeEnd: Boolean,
    extent: Dp = RailEdgeFadeExtent,
): Modifier {
    if (!fadeStart && !fadeEnd) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = size.width
            if (width <= 0f) return@drawWithContent
            val fadeFraction = (extent.toPx() / width).coerceIn(0f, 0.5f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to if (fadeStart) Color.Transparent else Color.Black,
                    fadeFraction to Color.Black,
                    1f - fadeFraction to Color.Black,
                    1f to if (fadeEnd) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/** Scrolls [targetIndex] fully into view with an 8dp margin. */
@Composable
fun EnsureRailItemFullyVisible(listState: LazyListState, targetIndex: Int) {
    val extraPaddingPx = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(listState, targetIndex) {
        if (targetIndex < 0) return@LaunchedEffect
        repeat(2) {
            val layoutInfo = listState.layoutInfo
            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (itemInfo == null) {
                listState.scrollToItem(targetIndex)
            } else {
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val itemStart = itemInfo.offset
                val itemEnd = itemInfo.offset + itemInfo.size
                when {
                    itemStart < viewportStart -> listState.animateScrollBy(itemStart - viewportStart - extraPaddingPx)
                    itemEnd > viewportEnd -> listState.animateScrollBy(itemEnd - viewportEnd + extraPaddingPx)
                    else -> return@LaunchedEffect
                }
                return@LaunchedEffect
            }
        }
    }
}

/** The bold page title, with a smaller title style when a subtitle sits under it. */
@Composable
fun TopBarTitleBlock(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxWidth: Dp = 120.dp,
) {
    Column(modifier = modifier.widthIn(max = maxWidth), verticalArrangement = Arrangement.Center) {
        Text(
            text = title,
            style = if (subtitle.isNullOrBlank()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Content of the expanded search pill: a search icon and the current query or the placeholder. */
@Composable
fun TopBarSearchPillContent(
    query: String,
    placeholder: String,
    searchIcon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = LocalInterfaceStyleTokens.current.topBarIconSize,
    searchLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = searchLabel,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(searchIcon, contentDescription = searchLabel, modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = query.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Android's category tabs rail: the selected tab is kept centred and underlined; [pagePosition] lets a pager
 * slide the underline between tabs while swiping.
 */
@Composable
fun TopBarTabsRail(
    items: List<TopBarTabItem>,
    selectedItemId: Long,
    onItemSelected: (Long) -> Unit,
    surface: RailSurface,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    pagePosition: (() -> Float)? = null,
    itemModifier: (TopBarTabItem) -> Modifier = { Modifier },
) {
    val tokens = LocalInterfaceStyleTokens.current
    val listState = rememberLazyListState()
    val selectedIndex = items.indexOfFirst { it.id == selectedItemId }
    val indicatorColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(selectedItemId, items) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            listState.scrollToItem(selectedIndex)
        }
        val distance = snapshotFlow {
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            if (item == null || layout.viewportSize.width == 0) null
            else item.offset + item.size / 2f - layout.viewportSize.width / 2f
        }.filterNotNull().first()
        listState.animateScrollBy(distance)
    }
    Box(modifier = modifier.height(tokens.minimumTouchTarget).padding(horizontal = horizontalPadding)) {
        surface(Modifier.fillMaxWidth().height(TopBarTabsRailVisualHeight).align(Alignment.Center))
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.minimumTouchTarget)
                .compactRailEdgeFade(fadeStart = listState.canScrollBackward, fadeEnd = listState.canScrollForward)
                .padding(horizontal = 8.dp)
                .drawWithContent {
                    drawContent()
                    val position = (pagePosition?.invoke() ?: selectedIndex.toFloat())
                        .coerceIn(0f, (items.size - 1).coerceAtLeast(0).toFloat())
                    val startIndex = floor(position).toInt()
                    val endIndex = (startIndex + 1).coerceAtMost(items.lastIndex)
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val startItem = visible.firstOrNull { it.index == startIndex }
                    val endItem = visible.firstOrNull { it.index == endIndex }
                    val first = startItem ?: endItem ?: return@drawWithContent
                    val second = endItem ?: first
                    val fraction = position - startIndex
                    val center = (first.offset + first.size / 2f) * (1f - fraction) +
                        (second.offset + second.size / 2f) * fraction
                    val width = (first.size * (1f - fraction) + second.size * fraction) * 0.56f
                    val y = size.height - 5.dp.toPx()
                    drawLine(
                        color = indicatorColor,
                        start = Offset(center - width / 2f, y),
                        end = Offset(center + width / 2f, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            items(items = items, key = { it.id }) { item ->
                val selected = item.id == selectedItemId
                Box(
                    modifier = itemModifier(item)
                        .height(tokens.minimumTouchTarget)
                        .clickable { onItemSelected(item.id) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.widthIn(max = 128.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Android's quick filter rail under the top bar. [leading] draws an optional icon; its flag says whether the
 * item is near the viewport, so hosts can defer network icon loads for far-away items.
 */
@Composable
fun <T> TopBarFilterRail(
    items: List<T>,
    key: (T) -> Any,
    title: (T) -> String,
    isSelected: (T) -> Boolean,
    onClick: (T) -> Unit,
    surface: RailSurface,
    modifier: Modifier = Modifier,
    leading: (@Composable (item: T, loadEnabled: Boolean) -> Unit)? = null,
    itemModifier: (T) -> Modifier = { Modifier },
) {
    val tokens = LocalInterfaceStyleTokens.current
    val listState = rememberLazyListState()
    val firstSelectedIndex = remember(items) { items.indexOfFirst(isSelected) }
    EnsureRailItemFullyVisible(listState = listState, targetIndex = firstSelectedIndex)
    LaunchedEffect(items, firstSelectedIndex) {
        if (firstSelectedIndex == 0 && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }
    // Keyed on layoutInfo directly, every measure pass (which always writes a new layoutInfo) recomposed the
    // rail, which measured again: the rail never settled. Only a changed range should recompose.
    val visibleItemRange by remember(listState) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val minVisible = visibleItems.minOfOrNull { it.index } ?: 0
            val maxVisible = visibleItems.maxOfOrNull { it.index } ?: -1
            (minVisible - 2).coerceAtLeast(0)..(maxVisible + 2).coerceAtLeast(-1)
        }
    }
    Box(modifier = modifier.fillMaxWidth().height(tokens.minimumTouchTarget)) {
        surface(Modifier.fillMaxWidth().height(TopBarFilterRailVisualHeight).align(Alignment.BottomCenter))
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(tokens.minimumTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(items = items, key = key) { item ->
                val itemIndex = remember(items, key(item)) { items.indexOfFirst { key(it) == key(item) } }
                val selected = isSelected(item)
                Box(
                    modifier = itemModifier(item)
                        .height(tokens.minimumTouchTarget)
                        .clickable { onClick(item) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Row(
                        modifier = Modifier.height(TopBarFilterRailVisualHeight),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading?.invoke(item, itemIndex in visibleItemRange)
                        Text(
                            text = title(item),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
