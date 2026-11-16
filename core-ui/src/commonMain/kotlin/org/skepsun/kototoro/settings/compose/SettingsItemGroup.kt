package org.skepsun.kototoro.settings.compose

import org.skepsun.kototoro.core.ui.theme.LocalIsIosInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalArtworkBackdrop
import org.skepsun.kototoro.core.ui.theme.ArtworkBackdrop
import org.skepsun.kototoro.core.ui.text.uiString
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.remember
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.ArtworkSurfaceRole
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.settings.LocalSettingsSearchTarget
import org.skepsun.kototoro.core.ui.settings.revealSettingsSearchTarget

enum class SettingsGroupItemPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

fun resolveSettingsGroupItemPosition(index: Int, total: Int): SettingsGroupItemPosition {
    require(total > 0) { "A settings group must contain at least one item" }
    require(index in 0 until total) { "Item index $index is outside a group of $total items" }
    return when {
        total == 1 -> SettingsGroupItemPosition.SINGLE
        index == 0 -> SettingsGroupItemPosition.FIRST
        index == total - 1 -> SettingsGroupItemPosition.LAST
        else -> SettingsGroupItemPosition.MIDDLE
    }
}

class SettingsItemGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()
    internal val searchKeys = mutableSetOf<String>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }

    fun item(searchKey: String, content: @Composable () -> Unit) {
        searchKeys += searchKey
        items += {
            Box(Modifier.fillMaxWidth().revealSettingsSearchTarget(searchKey)) {
                // The key already answers for this row; its title must not answer a second time.
                CompositionLocalProvider(LocalSettingsSearchTarget provides null) { content() }
            }
        }
    }
}

// Keep the builder non-composable so state changes invalidate and rebuild the complete item structure.
@Composable
fun SettingsPreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    itemModifier: (Int) -> Modifier = { Modifier },
    content: SettingsItemGroupScope.() -> Unit,
) {
    val scope = SettingsItemGroupScope()
    scope.content()
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (title.isNotBlank()) {
            androidx.compose.material3.Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = settingsSectionLabelColor(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsItemGroup(
            itemCount = scope.items.size,
            itemModifier = itemModifier,
        ) { index ->
            scope.items[index]()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsItemGroup(
    itemCount: Int,
    modifier: Modifier = Modifier,
    // Applied to each row's own Surface in the Material branch. zIndex only
    // reorders siblings inside the same parent, so anything that lifts a row
    // above later tiles (e.g. drag previews) has to live here, not on the row.
    itemModifier: (Int) -> Modifier = { Modifier },
    itemContent: @Composable (index: Int) -> Unit,
) {
    require(itemCount >= 0) { "Settings item count cannot be negative" }
    if (itemCount == 0) return

    val tokens = LocalInterfaceStyleTokens.current
    val containerColor = settingsGroupContainerColor()
    if (LocalIsIosInterfaceStyle.current) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
            color = containerColor,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                repeat(itemCount) { index ->
                    itemContent(index)
                    if (index != itemCount - 1) {
                        // iOS groups use one continuous surface with inset row separators.
                        SettingsGroupDivider(startPadding = 70.dp, endPadding = 0.dp)
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokens.settingsItemGap),
        ) {
            val pressMorph = LocalMaterialExpressiveComponentsEnabled.current
            repeat(itemCount) { index ->
                // Expressive segmented lists round a segment out to the group's outer corner while it is pressed.
                var pressed by remember { mutableStateOf(false) }
                val innerCornerRadius by animateDpAsState(
                    targetValue = if (pressed) {
                        tokens.settingsGroupOuterCornerRadius
                    } else {
                        tokens.settingsGroupInnerCornerRadius
                    },
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    label = "settings_segment_press",
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (pressMorph) Modifier.observeRowPress { pressed = it } else Modifier)
                        .then(itemModifier(index)),
                    shape = settingsGroupItemShape(
                        position = resolveSettingsGroupItemPosition(index, itemCount),
                        outerCornerRadius = tokens.settingsGroupOuterCornerRadius,
                        innerCornerRadius = innerCornerRadius.coerceAtLeast(0.dp),
                    ),
                    color = containerColor,
                ) {
                    itemContent(index)
                }
            }
        }
    }
}

/** A press inside a scrolling list waits this long, so a scroll gesture does not flash every row it starts on. */
private const val RowPressDelayMillis = 90L

/** A tap shorter than the delay still shows the press for this long. */
private const val RowTapPulseMillis = 90L

/**
 * Reports whether a control inside this row is being pressed, without taking part in the gesture: the row's own
 * clickable/toggleable keeps the click, and a row with nothing to press never reports.
 */
private fun Modifier.observeRowPress(onPressedChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Final pass: by now the row's control has consumed the down, and a scroll has consumed its moves.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (!down.isConsumed) return@awaitEachGesture
        var pressed = false
        var waited = 0L
        var released = false
        while (true) {
            val event = if (pressed) {
                awaitPointerEvent(PointerEventPass.Final)
            } else {
                withTimeoutOrNull((RowPressDelayMillis - waited).coerceAtLeast(1L)) {
                    awaitPointerEvent(PointerEventPass.Final)
                }
            }
            if (event == null) {
                pressed = true
                onPressedChange(true)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            waited = change.uptimeMillis - down.uptimeMillis
            if (!change.pressed) {
                released = true
                break
            }
            if (change.isConsumed && change.positionChanged()) break
        }
        if (!pressed && released) {
            onPressedChange(true)
            withTimeoutOrNull(RowTapPulseMillis) { while (true) awaitPointerEvent(PointerEventPass.Final) }
        }
        onPressedChange(false)
    }
}

/** The card color of a settings group (iOS) or of each of its rows (Material). */
@Composable
fun settingsGroupContainerColor(): Color =
    if (LocalArtworkBackdrop.current != ArtworkBackdrop.NONE) {
        // Settings groups are content cards over artwork — join the shared layering
        // for both interface styles instead of an iOS-only hardcoded alpha.
        MaterialTheme.colorScheme.surfaceContainerLow.artworkAwareContainerColor(ArtworkSurfaceRole.Card)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

private fun settingsGroupItemShape(
    position: SettingsGroupItemPosition,
    outerCornerRadius: Dp,
    innerCornerRadius: Dp,
): Shape = when (position) {
    SettingsGroupItemPosition.SINGLE -> RoundedCornerShape(outerCornerRadius)
    SettingsGroupItemPosition.FIRST -> RoundedCornerShape(
        topStart = outerCornerRadius,
        topEnd = outerCornerRadius,
        bottomStart = innerCornerRadius,
        bottomEnd = innerCornerRadius,
    )
    SettingsGroupItemPosition.MIDDLE -> RoundedCornerShape(innerCornerRadius)
    SettingsGroupItemPosition.LAST -> RoundedCornerShape(
        topStart = innerCornerRadius,
        topEnd = innerCornerRadius,
        bottomStart = outerCornerRadius,
        bottomEnd = outerCornerRadius,
    )
}

@Composable
fun SettingsCollapsiblePreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    enabled: Boolean = true,
    content: SettingsItemGroupScope.() -> Unit,
) {
    val scope = SettingsItemGroupScope()
    scope.content()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val searchTarget = LocalSettingsSearchTarget.current
    // A search jump is a navigation, not a toggle: the group must be at its final height on the first
    // frame, otherwise the reveal scroll below targets a row that is still expanding.
    val openedBySearch = searchTarget != null && searchTarget.key in scope.searchKeys
    LaunchedEffect(searchTarget) {
        if (openedBySearch) expanded = true
    }

    val tokens = LocalInterfaceStyleTokens.current
    val isIosStyle = LocalIsIosInterfaceStyle.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val horizontalPadding = if (expressive || isIosStyle) 16.dp else 20.dp
    val isArtworkBackground = (LocalArtworkBackdrop.current != ArtworkBackdrop.NONE)
    val containerColor = if (isArtworkBackground) {
        MaterialTheme.colorScheme.surfaceContainerLow.artworkAwareContainerColor(ArtworkSurfaceRole.Card)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
        color = containerColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(
                        enabled = enabled,
                        shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
                        addFocusTarget = false,
                    )
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .heightIn(min = tokens.settingsItemMinHeight)
                    .padding(horizontal = horizontalPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (summary != null) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        SettingsMaterialIcons.KeyboardArrowUp
                    } else {
                        SettingsMaterialIcons.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = if (openedBySearch) EnterTransition.None else expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsGroupDivider()
                    scope.items.forEachIndexed { index, itemContent ->
                        itemContent()
                        if (index != scope.items.lastIndex) {
                            SettingsGroupDivider()
                        }
                    }
                }
            }
        }
    }
}
