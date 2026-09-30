package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                    .padding(20.dp),
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
                                    .longPressDraggableHandle(enabled = !saving)
                                    .semantics {
                                        customActions = listOf(
                                            CustomAccessibilityAction(moveUp) {
                                                val index = ordered.indexOf(item)
                                                if (index > 0 && ordered[index].group == ordered[index - 1].group) {
                                                    ordered.add(index - 1, ordered.removeAt(index))
                                                    true
                                                } else false
                                            },
                                            CustomAccessibilityAction(moveDown) {
                                                val index = ordered.indexOf(item)
                                                if (index in 0 until ordered.lastIndex && ordered[index].group == ordered[index + 1].group) {
                                                    ordered.add(index + 1, ordered.removeAt(index))
                                                    true
                                                } else false
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
                    TextButton(enabled = !saving, onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !saving && !reorderableLazyListState.isAnyItemDragging && ordered.isNotEmpty(),
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
