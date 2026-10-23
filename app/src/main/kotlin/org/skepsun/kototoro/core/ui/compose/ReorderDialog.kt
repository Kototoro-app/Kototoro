package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ReorderItem(val key: String, val title: String, val group: String = "")

/** An explicit editing session: dragging never writes until Save is pressed. */
@Composable
fun ReorderDialog(
    items: List<ReorderItem>,
    onDismissRequest: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
) {
    val ordered = remember(items) { items.toMutableStateList() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val moveUp = stringResource(R.string.reorder_move_up)
    val moveDown = stringResource(R.string.reorder_move_down)
    val isTvPresentation = LocalUiPresentationConfig.current.isTv

    fun canMoveUp(item: ReorderItem): Boolean {
        val index = ordered.indexOf(item)
        return index > 0 && ordered[index].group == ordered[index - 1].group
    }

    fun canMoveDown(item: ReorderItem): Boolean {
        val index = ordered.indexOf(item)
        return index in 0 until ordered.lastIndex && ordered[index].group == ordered[index + 1].group
    }

    fun moveItemUp(item: ReorderItem) {
        val index = ordered.indexOf(item)
        if (canMoveUp(item)) {
            ordered.add(index - 1, ordered.removeAt(index))
        }
    }

    fun moveItemDown(item: ReorderItem) {
        val index = ordered.indexOf(item)
        if (canMoveDown(item)) {
            ordered.add(index + 1, ordered.removeAt(index))
        }
    }

    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        if (saving) return@rememberReorderableLazyListState
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex !in ordered.indices || toIndex !in ordered.indices || fromIndex == toIndex) {
            return@rememberReorderableLazyListState
        }
        if (ordered[fromIndex].group != ordered[toIndex].group) {
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
                    text = stringResource(R.string.reorder),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.reorder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
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
                                    .longPressDraggableHandle(enabled = !saving && !isTvPresentation)
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
                                    if (isTvPresentation) {
                                        // A remote cannot drag: expose the same moves the accessibility
                                        // actions already provide, one button per direction.
                                        ReorderMoveButton(
                                            icon = Icons.Filled.KeyboardArrowUp,
                                            contentDescription = moveUp,
                                            enabled = !saving && canMoveUp(item),
                                            onClick = { moveItemUp(item) },
                                        )
                                        ReorderMoveButton(
                                            icon = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = moveDown,
                                            enabled = !saving && canMoveDown(item),
                                            onClick = { moveItemDown(item) },
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .draggableHandle(enabled = !saving),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_reorder_handle),
                                                contentDescription = stringResource(R.string.reorder),
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
                        modifier = Modifier.tvFocusable(
                            enabled = !saving,
                            shape = RoundedCornerShape(20.dp),
                            addFocusTarget = false,
                        ),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSave = !saving && !reorderableLazyListState.isAnyItemDragging && ordered.isNotEmpty()
                    Button(
                        enabled = canSave,
                        modifier = Modifier.tvFocusable(
                            enabled = canSave,
                            shape = RoundedCornerShape(20.dp),
                            addFocusTarget = false,
                        ),
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    onSave(ordered.map(ReorderItem::key))
                                    onDismissRequest()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.localizedMessage ?: e.javaClass.simpleName
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.save))
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
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
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
