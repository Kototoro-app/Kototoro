package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

enum class SelectionAction {
    SELECT_ALL, SHARE, FAVOURITE, SAVE, EDIT_OVERRIDE, MIGRATE, REMOVE, PIN, MARK_AS_COMPLETED,
}

/** Hosts resolve labels/icons and capabilities; layout and action dispatch are shared. */
data class SelectionToolbarItem(
    val action: SelectionAction,
    val label: String,
    val icon: Painter? = null,
    val enabled: Boolean = true,
    val tag: String = "selection-action:${action.name}",
)

/** Android keeps its glass/root-overlay popup and focus restoration around this shared menu content. */
class SelectionOverflowContent(
    val expanded: Boolean,
    val onDismiss: () -> Unit,
    val anchorBounds: Rect?,
    val anchorFocus: FocusRequester,
    val content: @Composable ColumnScope.() -> Unit,
)

@Composable
fun SelectionOverflowMenu(menu: SelectionOverflowContent) {
    DropdownMenu(menu.expanded, menu.onDismiss, content = menu.content)
}

/** Android's close/count/action-group toolbar, with overflow determined by the space actually available. */
@Composable
fun ContentSelectionToolbar(
    countLabel: String,
    items: List<SelectionToolbarItem>,
    preferredInline: List<SelectionAction>,
    onClear: () -> Unit,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tagPrefix: String = "selection",
    surface: @Composable (Boolean, @Composable BoxScope.() -> Unit) -> Unit = { _, body ->
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) { Box(content = body) }
    },
    renderOverflow: @Composable (SelectionOverflowContent) -> Unit = { SelectionOverflowMenu(it) },
) {
    val tokens = LocalInterfaceStyleTokens.current
    val buttonSize = tokens.topBarButtonSize
    val iconSize = tokens.topBarIconSize
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val anchorFocus = remember { FocusRequester() }
    Row(modifier.fillMaxWidth().height(tokens.mainTopBarHeight).testTag("$tagPrefix-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        surface(true) {
            IconButton(onClear, modifier = Modifier.size(buttonSize).tvIconButtonRing().testTag("$tagPrefix-cancel")) {
                Icon(ReaderPanelIcons.TtsClose, uiString("close"), modifier = Modifier.size(iconSize))
            }
        }
        Text(countLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, modifier = Modifier.testTag("$tagPrefix-count"))
        BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            val capacity = ((maxWidth - 4.dp).value / (buttonSize + 2.dp).value).toInt().coerceAtLeast(0)
            val preferred = preferredInline.distinct().mapNotNull { action -> items.find { it.action == action } }
            val needsMenu = preferred.size < items.size || preferred.size > capacity
            val inline = preferred.take((capacity - if (needsMenu) 1 else 0).coerceAtLeast(0))
            val overflow = items.filter { item -> inline.none { it.action == item.action } }
            LaunchedEffect(overflow.isEmpty()) { if (overflow.isEmpty()) expanded = false }
            surface(false) {
                Row(Modifier.padding(horizontal = 2.dp).height(buttonSize),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    inline.forEach { item ->
                        IconButton({ onAction(item.action) }, enabled = enabled && item.enabled,
                            modifier = Modifier.size(buttonSize).tvIconButtonRing().testTag(item.tag)) {
                            if (item.icon != null) Icon(item.icon, item.label, modifier = Modifier.size(iconSize))
                            else Text(item.label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (overflow.isNotEmpty()) Box(Modifier.onGloballyPositioned { anchorBounds = it.boundsInRoot() }) {
                        IconButton({ expanded = true }, modifier = Modifier.focusRequester(anchorFocus)
                            .size(buttonSize).tvIconButtonRing().testTag("$tagPrefix-more")) {
                            Icon(ReaderPanelIcons.MoreVert, uiString("more"), modifier = Modifier.size(iconSize))
                        }
                        renderOverflow(SelectionOverflowContent(expanded, { expanded = false }, anchorBounds, anchorFocus) {
                            overflow.forEach { item ->
                                DropdownMenuItem(text = { Text(item.label) }, enabled = enabled && item.enabled,
                                    onClick = { expanded = false; onAction(item.action) },
                                    modifier = Modifier.testTag(item.tag).tvFocusable(enabled = enabled && item.enabled,
                                        shape = RoundedCornerShape(10.dp), addFocusTarget = false))
                            }
                        })
                    }
                }
            }
        }
    }
}
