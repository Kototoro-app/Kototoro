package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ReorderItem(val key: String, val title: String, val group: String = "")

/** An explicit editing session: dragging never writes until Save is pressed. */
@Composable
fun ReorderEditorDialog(
    items: List<ReorderItem>,
    onDismissRequest: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
    enabled: Boolean = true,
    allowEmptySave: Boolean = false,
    saveOnMove: Boolean = false,
    title: String = uiString("reorder"),
    footerText: String? = null,
    showMoveButtons: Boolean = false,
    summary: String = uiString("reorder_hint"),
    emptyText: String? = null,
    tagPrefix: String = "reorder",
) {
    val ordered = remember(items) { items.toMutableStateList() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val moveUp = uiString("reorder_move_up")
    val moveDown = uiString("reorder_move_down")
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val currentEnabled by rememberUpdatedState(enabled)
    fun isActive(): Boolean = currentEnabled && !saving
    val active = isActive()

    fun commit(closeAfterSave: Boolean) {
        if (!isActive()) return
        val keys = ordered.map(ReorderItem::key)
        saving = true
        error = null
        scope.launch {
            try {
                onSave(keys)
                if (closeAfterSave) onDismissRequest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                saving = false
            }
        }
    }

    fun canMoveUp(item: ReorderItem): Boolean {
        val index = ordered.indexOf(item)
        return isActive() && index > 0 && ordered[index].group == ordered[index - 1].group
    }

    fun canMoveDown(item: ReorderItem): Boolean {
        val index = ordered.indexOf(item)
        return isActive() && index in 0 until ordered.lastIndex && ordered[index].group == ordered[index + 1].group
    }

    fun moveItemUp(item: ReorderItem) {
        val index = ordered.indexOf(item)
        if (canMoveUp(item)) {
            ordered.add(index - 1, ordered.removeAt(index))
            if (saveOnMove) commit(false)
        }
    }

    fun moveItemDown(item: ReorderItem) {
        val index = ordered.indexOf(item)
        if (canMoveDown(item)) {
            ordered.add(index + 1, ordered.removeAt(index))
            if (saveOnMove) commit(false)
        }
    }

    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        if (!isActive()) return@rememberReorderableLazyListState
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex !in ordered.indices || toIndex !in ordered.indices || fromIndex == toIndex) {
            return@rememberReorderableLazyListState
        }
        if (ordered.subList(minOf(fromIndex, toIndex), maxOf(fromIndex, toIndex) + 1)
                .any { it.group != ordered[fromIndex].group }) {
            return@rememberReorderableLazyListState
        }
        ordered.add(toIndex, ordered.removeAt(fromIndex))
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .testTag(tagPrefix + "-dialog")
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .fillMaxHeight(0.85f),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .tvTakeInitialFocus(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (ordered.isEmpty() && emptyText != null) {
                    Text(emptyText, modifier = Modifier.testTag(tagPrefix + "-empty"))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag(tagPrefix + "-list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ordered, key = { it.key }) { item ->
                        ReorderableItem(reorderableLazyListState, key = item.key) { isDragging ->
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label = "reorder_elevation",
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(tagPrefix + ":" + item.key)
                                    .longPressDraggableHandle(enabled = active && !isTvPresentation && !saveOnMove)
                                    .semantics {
                                        customActions = listOf(
                                            CustomAccessibilityAction(moveUp) {
                                                if (canMoveUp(item)) {
                                                    moveItemUp(item)
                                                    true
                                                } else {
                                                    false
                                                }
                                            },
                                            CustomAccessibilityAction(moveDown) {
                                                if (canMoveDown(item)) {
                                                    moveItemDown(item)
                                                    true
                                                } else {
                                                    false
                                                }
                                            },
                                        )
                                    },
                                shape = MaterialTheme.shapes.medium,
                                color = if (isDragging) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                tonalElevation = elevation,
                                shadowElevation = elevation,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (item.group.isNotEmpty()) {
                                            Text(
                                                text = item.group,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (isTvPresentation || showMoveButtons || saveOnMove) {
                                        // A remote cannot drag: expose the same moves the accessibility
                                        // actions already provide, one button per direction.
                                        ReorderMoveButton(
                                            icon = SettingsMaterialIcons.KeyboardArrowUp,
                                            modifier = Modifier.testTag(tagPrefix + "-up:" + item.key),
                                            contentDescription = moveUp,
                                            enabled = canMoveUp(item),
                                            onClick = { moveItemUp(item) },
                                        )
                                        ReorderMoveButton(
                                            icon = SettingsMaterialIcons.KeyboardArrowDown,
                                            modifier = Modifier.testTag(tagPrefix + "-down:" + item.key),
                                            contentDescription = moveDown,
                                            enabled = canMoveDown(item),
                                            onClick = { moveItemDown(item) },
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .testTag(tagPrefix + "-handle:" + item.key)
                                                .draggableHandle(enabled = active),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = SettingsMaterialIcons.Reorder,
                                                contentDescription = uiString("reorder"),
                                                tint = if (isDragging) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (footerText != null) {
                    Text(footerText, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(tagPrefix + "-note").padding(top = 8.dp))
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !saving,
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag(tagPrefix + if (saveOnMove) "-close" else "-cancel").tvFocusable(
                            enabled = !saving,
                            shape = RoundedCornerShape(20.dp),
                            addFocusTarget = false,
                        ),
                    ) {
                        Text(if (saveOnMove) uiString("close") else uiString("cancel"))
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSave = active && !reorderableLazyListState.isAnyItemDragging &&
                        (ordered.isNotEmpty() || allowEmptySave)
                    if (!saveOnMove) Button(
                        enabled = canSave,
                        modifier = Modifier.testTag(tagPrefix + "-save").tvFocusable(
                            enabled = canSave,
                            shape = RoundedCornerShape(20.dp),
                            addFocusTarget = false,
                        ),
                        onClick = { commit(true) },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(uiString("save"))
                    }
                }
            }
        }
    }
}

/**
 * One move-up / move-down stop of a reorder row for the TV presentation.
 *
 * A remote cannot drag a list, so the row exposes the two moves that already exist as accessibility
 * actions. A move that would leave the item's group stays disabled: a disabled IconButton is not a
 * focus target, so the D-pad never stops on a button that cannot do anything.
 */
@Composable
private fun ReorderMoveButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(40.dp)
            .tvFocusable(enabled = enabled, shape = CircleShape, addFocusTarget = false),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}
