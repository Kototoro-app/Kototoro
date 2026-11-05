package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TabletNavigationDestination(val id: Int, val title: String)

/** Width of every control in the vertical bar: the circular buttons and the destination capsule. */
val TabletVerticalBarControlWidth: Dp = 56.dp

/**
 * The size the bar gives its circular controls (header and accessory). Hosts size those buttons with it,
 * because a short bar shrinks them instead of dropping them.
 */
val LocalTabletVerticalBarControlSize = staticCompositionLocalOf { TabletVerticalBarControlWidth }

private val CapsuleInset = 4.dp
private val ItemHeight = 48.dp
private val LabelledItemHeight = 58.dp
private val GroupGap = 12.dp

/** The bar's metrics at one compression step: circular controls, the gaps around them, destinations. */
internal data class VerticalBarLayout(val controlSize: Dp, val gap: Dp, val itemHeight: Dp)

/** Roomiest first; a short bar takes the first step that fits and the smallest one when none does. */
private val CompressionSteps = listOf(
    VerticalBarLayout(controlSize = 56.dp, gap = GroupGap, itemHeight = ItemHeight),
    VerticalBarLayout(controlSize = 48.dp, gap = 8.dp, itemHeight = 44.dp),
    VerticalBarLayout(controlSize = 40.dp, gap = 6.dp, itemHeight = 40.dp),
)

/**
 * The tablet navigation bar laid out like iPhone Duo's vertical bar: one column along the screen edge,
 * the header control and the page's actions on top, a flexible gap, then the accessory and the destinations grouped into a
 * single capsule at the bottom. Destinations are symbol-only unless [showLabels] is set, because the bar
 * has a fixed width and flexible height.
 *
 * Every control stays in the column. When it is too short the header, the accessory, the gaps and the
 * destinations shrink together in steps (labels go first), so nothing leaves the bar; its width never changes.
 *
 * Hosts draw the surfaces: [capsule] renders the destination group's material (glass on Android) and the
 * header/accessory bring their own circular surfaces sized to [LocalTabletVerticalBarControlSize].
 */
@Composable
fun TabletVerticalBar(
    destinations: List<TabletNavigationDestination>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    icon: @Composable (Int, Boolean) -> Unit,
    capsule: @Composable (Modifier, @Composable BoxScope.() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    enabled: Boolean = true,
    selectedContentColor: Color = MaterialTheme.colorScheme.primary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    header: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    accessory: (@Composable () -> Unit)? = null,
    itemModifier: (Int) -> Modifier = { Modifier },
) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val layout = remember(maxHeight, header != null, accessory != null, showLabels, destinations.size) {
            resolveVerticalBarLayout(
                availableHeight = maxHeight,
                hasHeader = header != null,
                hasAccessory = accessory != null,
                itemCount = destinations.size,
                showLabels = showLabels,
            )
        }
        CompositionLocalProvider(LocalTabletVerticalBarControlSize provides layout.controlSize) {
        Column(
            modifier = Modifier.fillMaxHeight().width(TabletVerticalBarControlWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            header?.invoke()
            // The page's own symbol actions (the top bar's upper group) follow the header and take the
            // flexible space, scrolling rather than pushing the destinations when the bar is short.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (actions != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (header != null) Spacer(Modifier.height(layout.gap))
                        actions()
                    }
                }
            }
            if (accessory != null) {
                accessory()
                Spacer(Modifier.height(layout.gap))
            }
            capsule(Modifier.width(TabletVerticalBarControlWidth)) {
                DestinationColumn(
                    destinations = destinations,
                    selectedId = selectedId,
                    itemHeight = layout.itemHeight,
                    showLabels = showLabels && layout.itemHeight >= LabelledItemHeight,
                    enabled = enabled,
                    selectedContentColor = selectedContentColor,
                    unselectedContentColor = unselectedContentColor,
                    indicatorColor = indicatorColor,
                    onSelect = onSelect,
                    onReselect = onReselect,
                    icon = icon,
                    itemModifier = itemModifier,
                )
            }
        }
        }
    }
}

@Composable
private fun DestinationColumn(
    destinations: List<TabletNavigationDestination>,
    selectedId: Int,
    itemHeight: Dp,
    showLabels: Boolean,
    enabled: Boolean,
    selectedContentColor: Color,
    unselectedContentColor: Color,
    indicatorColor: Color,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    icon: @Composable (Int, Boolean) -> Unit,
    itemModifier: (Int) -> Modifier,
) {
    val itemWidth = TabletVerticalBarControlWidth - CapsuleInset * 2
    val selectedIndex = destinations.indexOfFirst { it.id == selectedId }
    val indicatorOffset by animateDpAsState(
        targetValue = itemHeight * selectedIndex.coerceAtLeast(0),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "tabletVerticalBarIndicator",
    )
    Box(modifier = Modifier.padding(CapsuleInset)) {
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(y = indicatorOffset)
                    .size(itemWidth, itemHeight)
                    .background(indicatorColor, CircleShape),
            )
        }
        Column {
            destinations.forEach { destination ->
                val selected = destination.id == selectedId
                Column(
                    modifier = itemModifier(destination.id)
                        .size(itemWidth, itemHeight)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (selected) onReselect(destination.id) else onSelect(destination.id)
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (selected) selectedContentColor else unselectedContentColor,
                    ) {
                        icon(destination.id, selected)
                        if (showLabels) {
                            Text(
                                text = destination.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first [CompressionSteps] entry whose column fits; labels only ride on the roomiest step. Fixed parts:
 * header, accessory (each a circular control plus a gap) and the capsule; the actions take what is left.
 */
internal fun resolveVerticalBarLayout(
    availableHeight: Dp,
    hasHeader: Boolean,
    hasAccessory: Boolean,
    itemCount: Int,
    showLabels: Boolean,
): VerticalBarLayout {
    fun VerticalBarLayout.height(): Dp {
        val circles = (if (hasHeader) 1 else 0) + (if (hasAccessory) 1 else 0)
        return (controlSize + gap) * circles + itemHeight * itemCount + CapsuleInset * 2
    }
    if (showLabels) {
        val labelled = CompressionSteps.first().copy(itemHeight = LabelledItemHeight)
        if (labelled.height() <= availableHeight) return labelled
    }
    return CompressionSteps.firstOrNull { it.height() <= availableHeight } ?: CompressionSteps.last()
}

/**
 * Whether the page's action group fits in the vertical bar next to everything that outranks it. Toolbar items
 * give way first on iPhone Duo, so the actions only move into the bar when the header, the accessory and the
 * destinations at their preferred size still fit; otherwise they stay in the top bar. Hosts decide this from the
 * window size, never from the bar's own measurement, so moving the actions cannot flip the answer.
 */
fun tabletVerticalBarFitsActions(
    availableHeight: Dp,
    hasHeader: Boolean,
    actionsHeight: Dp,
    hasAccessory: Boolean,
    itemCount: Int,
    showLabels: Boolean,
): Boolean {
    val header = if (hasHeader) TabletVerticalBarControlWidth + GroupGap else 0.dp
    val accessory = if (hasAccessory) TabletVerticalBarControlWidth + GroupGap else 0.dp
    val itemHeight = if (showLabels) LabelledItemHeight else ItemHeight
    val capsule = itemHeight * itemCount + CapsuleInset * 2
    return header + actionsHeight + GroupGap + accessory + capsule <= availableHeight
}
