package org.skepsun.kototoro.core.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Hosts map their destination IDs and availability hints; navigation policy and persistence stay in the host. */
data class NavigationSettingsItem(
    val id: String,
    val title: String,
    val summary: String? = null,
    val icon: Painter? = null,
)

/** The complete editor shared by Android and desktop, including the drag draft and add picker. */
@Composable
fun NavigationSettingsContent(
    configuredItems: List<NavigationSettingsItem>,
    availableItems: List<NavigationSettingsItem>,
    canShowAddAction: Boolean,
    canAddAction: Boolean,
    onAddItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showMoveButtons: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    rowShape: Shape = RoundedCornerShape(20.dp),
    rowColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    dragHandle: Painter? = null,
    addIcon: Painter? = null,
    removeButtonModifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    val localItems = remember { mutableStateListOf<NavigationSettingsItem>().also { it.addAll(configuredItems) } }
    val listState = rememberLazyListState()
    var dragInitialIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    val currentOnReorder by rememberUpdatedState(onReorder)
    LaunchedEffect(configuredItems) {
        localItems.clear()
        localItems.addAll(configuredItems)
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // The section heading occupies list index zero.
        val start = from.index - 1
        val end = to.index - 1
        if (enabled && start in localItems.indices && end in localItems.indices && start != end) {
            if (dragInitialIndex < 0) dragInitialIndex = start
            localItems.add(end, localItems.removeAt(start))
            dragCurrentIndex = end
        }
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) {
            if (dragInitialIndex >= 0 && dragCurrentIndex >= 0 && dragInitialIndex != dragCurrentIndex) {
                currentOnReorder(dragInitialIndex, dragCurrentIndex)
            }
            dragInitialIndex = -1
            dragCurrentIndex = -1
        }
    }
    val moveUp = uiString("move_up")
    val moveDown = uiString("move_down")
    val reorder = uiString("reorder")
    val remove = uiString("remove")
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("navigation-settings-content"),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "nav_section_header") { SettingsSectionHeader(uiString("main_screen_sections")) }
        items(localItems, key = { it.id }) { item ->
            ReorderableItem(reorderState, key = item.id) { dragging ->
                val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "nav_elevation")
                val index = localItems.indexOf(item)
                fun move(direction: Int) {
                    val from = localItems.indexOfFirst { it.id == item.id }
                    val to = from + direction
                    if (enabled && from in localItems.indices && to in localItems.indices) {
                        localItems.add(to, localItems.removeAt(from))
                        currentOnReorder(from, to)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("nav-config-item:${item.id}")
                        .then(if (enabled) Modifier.longPressDraggableHandle() else Modifier),
                    shape = rowShape,
                    color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else rowColor,
                    tonalElevation = elevation,
                    shadowElevation = elevation,
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        item.icon?.let {
                            Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.summary?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (showMoveButtons) {
                            TextButton({ move(-1) }, enabled = enabled && index > 0,
                                modifier = Modifier.testTag("nav-config-up:${item.id}")
                                    .semantics { contentDescription = moveUp }) { Text("↑") }
                            TextButton({ move(1) }, enabled = enabled && index < localItems.lastIndex,
                                modifier = Modifier.testTag("nav-config-down:${item.id}")
                                    .semantics { contentDescription = moveDown }) { Text("↓") }
                        } else {
                            Box(Modifier.size(48.dp).testTag("nav-config-reorder:${item.id}").semantics {
                                contentDescription = reorder
                                customActions = buildList {
                                    if (enabled && index > 0) add(CustomAccessibilityAction(moveUp) { move(-1); true })
                                    if (enabled && index < localItems.lastIndex) {
                                        add(CustomAccessibilityAction(moveDown) { move(1); true })
                                    }
                                }
                            }.then(if (enabled) Modifier.draggableHandle() else Modifier),
                                contentAlignment = Alignment.Center) {
                                if (dragHandle != null) Icon(dragHandle, null, Modifier.size(22.dp),
                                    tint = if (dragging) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                else Text("≡")
                            }
                        }
                        IconButton({ onRemoveItem(item.id) }, enabled = enabled,
                            modifier = removeButtonModifier.testTag("nav-config-remove:${item.id}")) {
                            Icon(ReaderPanelIcons.Delete, contentDescription = remove)
                        }
                    }
                }
            }
        }
        if (canShowAddAction) item(key = "nav_add_action") {
            Surface(shape = rowShape, color = rowColor) {
                SettingsValueRow(
                    title = if (canAddAction) uiString("add") else uiString("items_limit_exceeded"),
                    summary = null, value = null, enabled = enabled && canAddAction,
                    onClick = { addDialogVisible = true }, testTag = "nav-config-add",
                    leadingIcon = addIcon,
                    modifier = Modifier.tvFocusable(enabled = enabled && canAddAction, addFocusTarget = false),
                )
            }
        }
        item(key = "nav_footer") { footer() }
    }
    if (addDialogVisible) {
        renderDialog(PreferenceDialogContent(
            title = uiString("add"), onDismiss = { addDialogVisible = false },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("nav-config-add-dialog")) {
                    items(availableItems, key = { it.id }) { item ->
                        SettingsValueRow(item.title, null, null, enabled && canAddAction, {
                            onAddItem(item.id)
                            addDialogVisible = false
                        }, testTag = "nav-config-add-option:${item.id}", leadingIcon = item.icon,
                            modifier = Modifier.tvFocusable(enabled = enabled && canAddAction, addFocusTarget = false))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton({ addDialogVisible = false }, modifier = Modifier.testTag("nav-config-add-cancel")) {
                    Text(uiString("cancel"))
                }
            },
        ))
    }
}
