package org.skepsun.kototoro.core.ui.topbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText

/**
 * One action of a top-bar capsule. It stays in the capsule while the bar is at least [minWidth] wide and moves to the
 * "more" menu below that; [overflowOnly] items live in the menu only. An item with a [menu] opens that menu from its
 * own button and is never moved to "more".
 */
class TopBarActionItem(
    val key: String,
    val icon: Painter,
    val label: String,
    val onClick: () -> Unit = {},
    val enabled: Boolean = true,
    val active: Boolean = false,
    val badge: Boolean = false,
    /** Unspecified: the capsule's content color. */
    val tint: Color = Color.Unspecified,
    val minWidth: Dp = 0.dp,
    val overflowOnly: Boolean = false,
    /** A rule above this item when it sits in the "more" menu. */
    val dividerBefore: Boolean = false,
    val modifier: Modifier = Modifier,
    val menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
)

/** What a host menu needs to place itself under the button that opened it and to return focus to it. */
class TopBarMenuAnchor(val bounds: Rect?, val focusRequester: FocusRequester)

/** The host's popup: Android's glass menu, the desktop's Material menu. */
typealias TopBarMenuHost = @Composable (
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: TopBarMenuAnchor,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit

/** The pill surface a host draws behind the back button and the action capsule. */
typealias TopBarCapsuleSurface = @Composable (modifier: Modifier, content: @Composable BoxScope.() -> Unit) -> Unit

/**
 * Android's source-results top bar (`SourceListTopActionsRow`): a back button, the source title and the action
 * capsule, whose lower-priority buttons fold into "more" as the bar narrows.
 */
@Composable
fun SourceResultsTopBar(
    title: String,
    onBack: () -> Unit,
    backIcon: @Composable () -> Unit,
    backLabel: String,
    actions: List<TopBarActionItem>,
    moreIcon: Painter,
    moreLabel: String,
    surface: TopBarCapsuleSurface,
    menuHost: TopBarMenuHost,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    controlSize: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    itemSpacing: Dp = 8.dp,
    chromeAlpha: Float = 1f,
    backModifier: Modifier = Modifier,
    moreModifier: Modifier = Modifier,
    /** Composed first inside the capsule, before the actions (Android's space switcher). */
    capsuleLeading: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val direct = actions.filter { !it.overflowOnly && (it.menu != null || width >= it.minWidth) }
        val overflow = actions.filter { it !in direct }
        Row(
            modifier = modifier.fillMaxWidth().height(height),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            surface(Modifier.wrapContentWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = backModifier
                        .size(controlSize)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { backIcon() }
                }
            }
            androidx.compose.material3.Text(
                text = title,
                modifier = Modifier.weight(1f).alpha(chromeAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (direct.isNotEmpty() || overflow.isNotEmpty() || capsuleLeading != null) {
                surface(Modifier.wrapContentWidth()) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides controlSize) {
                        Row(
                            modifier = Modifier.height(controlSize).padding(horizontal = 2.dp).alpha(chromeAlpha),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            capsuleLeading?.invoke()
                            direct.forEach { item -> CapsuleAction(item, controlSize, iconSize, menuHost) }
                            if (overflow.isNotEmpty()) {
                                MenuButton(moreIcon, moreLabel, moreModifier, controlSize, iconSize,
                                    MaterialTheme.colorScheme.onSurface, menuHost) { dismiss ->
                                    overflow.forEachIndexed { index, item ->
                                        if (item.dividerBefore && index > 0) HorizontalDivider()
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(item.label) },
                                            onClick = { dismiss(); item.onClick() },
                                            modifier = item.modifier,
                                            enabled = item.enabled,
                                            leadingIcon = { Icon(item.icon, contentDescription = null) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleAction(item: TopBarActionItem, controlSize: Dp, iconSize: Dp, menuHost: TopBarMenuHost) {
    val tint = item.tint.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
    val menu = item.menu
    if (menu != null) {
        MenuButton(item.icon, item.label, item.modifier, controlSize, iconSize, tint, menuHost,
            active = item.active, badge = item.badge, enabled = item.enabled) { dismiss -> menu(dismiss) }
    } else {
        TopBarIconAction(
            icon = item.icon,
            contentDescription = item.label,
            onClick = item.onClick,
            modifier = item.modifier,
            active = item.active,
            badge = item.badge,
            enabled = item.enabled,
            size = controlSize,
            iconSize = iconSize,
            inactiveTint = tint,
            focusShape = CircleShape,
        )
    }
}

/** A capsule button that opens [content] in the host's menu, anchored under itself. */
@Composable
private fun MenuButton(
    icon: Painter,
    label: String,
    modifier: Modifier,
    controlSize: Dp,
    iconSize: Dp,
    tint: Color,
    menuHost: TopBarMenuHost,
    active: Boolean = false,
    badge: Boolean = false,
    enabled: Boolean = true,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val focus = remember { FocusRequester() }
    Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
        TopBarIconAction(
            icon = icon,
            contentDescription = label,
            onClick = { expanded = !expanded },
            modifier = modifier.focusRequester(focus),
            active = active,
            badge = badge,
            enabled = enabled,
            size = controlSize,
            iconSize = iconSize,
            inactiveTint = tint,
            focusShape = CircleShape,
        )
        val dismiss = { expanded = false }
        menuHost(expanded && enabled, dismiss, TopBarMenuAnchor(bounds, focus)) { content(dismiss) }
    }
}
