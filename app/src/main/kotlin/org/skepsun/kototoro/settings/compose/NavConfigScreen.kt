package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.parsers.util.move
import org.skepsun.kototoro.settings.nav.model.NavItemConfigModel
import kotlin.math.abs

/**
 * The list data is deliberately left untouched while a row is being dragged:
 * rows here are positionally reused inside [SettingsPreferenceGroup], so a live
 * reorder would rebuild each row in place and restart (i.e. kill) the drag
 * gesture that lives on the handle. Instead the dragged row follows the finger
 * via a layer offset, its neighbours spring out of the way as the target slot
 * changes, and the real move is committed once on release.
 */
@Composable
fun NavConfigScreen(
    configuredItems: List<NavItemConfigModel>,
    availableItems: List<NavItem>,
    canShowAddAction: Boolean,
    canAddAction: Boolean,
    onAddItem: (NavItem) -> Unit,
    onRemoveItem: (NavItem) -> Unit,
    onMoveItem: (item: NavItem, direction: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAddDialogVisible by remember { mutableStateOf(false) }
    val rowCenters = remember { mutableStateMapOf<NavItem, Float>() }
    var dragState by remember { mutableStateOf<NavRowDragState?>(null) }
    val dragScope = rememberCoroutineScope()
    val currentOnMoveItem by rememberUpdatedState(onMoveItem)

    val currentOrder = remember(configuredItems) { configuredItems.map { it.item } }
    // Stop painting the drag offsets the moment the committed order arrives;
    // keeping them would stack stale translations on top of the new layout.
    val visualDragState = dragState?.takeUnless { it.committedOrder == currentOrder }
    val dragSourceIndex = visualDragState?.sourceIndex
    val dragCurrentIndex = visualDragState?.currentIndex
    val draggedItem = visualDragState?.item

    LaunchedEffect(configuredItems) {
        val expected = dragState?.committedOrder ?: return@LaunchedEffect
        if (configuredItems.mapTo(mutableListOf()) { it.item } == expected) {
            dragState = null
        }
    }
    dragState?.let { state ->
        if (state.committedOrder != null) {
            // Failsafe: never wedge the screen if the committed order never lands.
            LaunchedEffect(state) {
                delay(1500L)
                if (dragState === state) {
                    dragState = null
                }
            }
        }
    }

    fun rowCenterPositions(): List<Float>? {
        if (configuredItems.isEmpty()) return null
        return configuredItems.map { rowCenters[it.item] ?: return null }
    }

    fun rowShiftPx(item: NavItem, index: Int): Float {
        val source = dragSourceIndex ?: return 0f
        val current = dragCurrentIndex ?: return 0f
        if (item == draggedItem) return 0f
        val centers = rowCenterPositions() ?: return 0f
        return when {
            source < current && index > source && index <= current -> centers[index - 1] - centers[index]
            source > current && index >= current && index < source -> centers[index + 1] - centers[index]
            else -> 0f
        }
    }

    fun handleDragStart(item: NavItem, index: Int) {
        if (dragState == null) {
            dragState = NavRowDragState(item = item, sourceIndex = index)
        }
    }

    fun handleDrag(deltaY: Float) {
        val state = dragState ?: return
        if (state.committedOrder != null) return
        val centers = rowCenterPositions() ?: return
        val ownCenter = centers.getOrNull(state.sourceIndex) ?: return
        if (state.fingerCenter.isNaN()) {
            state.fingerCenter = ownCenter
        }
        state.fingerCenter += deltaY
        val fingerCenter = state.fingerCenter
        // Target slot = row whose static center sits closest to the finger.
        val targetIndex = centers.indices
            .minByOrNull { index -> abs(centers[index] - fingerCenter) }
            ?: state.currentIndex
        val targetCenter = centers[targetIndex]
        // The row follows the finger freely but cannot outrun the slot it is
        // about to take. The clamp must read the raw finger position, never its
        // own previous output - feeding the clamped offset back in pins the row
        // to the first slot center and multi-step drags can never continue.
        val visualCenter = if (targetCenter >= ownCenter) {
            targetCenter.coerceAtMost(fingerCenter)
        } else {
            targetCenter.coerceAtLeast(fingerCenter)
        }
        state.currentIndex = targetIndex
        val nextOffset = visualCenter - ownCenter
        dragScope.launch { state.offsetAnim.snapTo(nextOffset) }
    }

    fun handleDragEnd() {
        val state = dragState ?: return
        if (state.committedOrder != null) return
        if (state.currentIndex == state.sourceIndex) {
            dragScope.launch {
                state.offsetAnim.animateTo(0f)
                if (dragState === state) {
                    dragState = null
                }
            }
            return
        }
        val centers = rowCenterPositions()
        val targetOffset = if (centers != null) {
            (centers.getOrNull(state.currentIndex) ?: state.offsetAnim.value + centers[state.sourceIndex]) -
                centers[state.sourceIndex]
        } else {
            state.offsetAnim.value
        }
        state.committedOrder = configuredItems.map { it.item }
            .toMutableList()
            .apply { move(state.sourceIndex, state.currentIndex) }
        dragScope.launch { state.offsetAnim.snapTo(targetOffset) }
        currentOnMoveItem(state.item, state.currentIndex - state.sourceIndex)
    }

    fun handleDragCancel() {
        val state = dragState ?: return
        if (state.committedOrder != null) return
        dragScope.launch {
            state.offsetAnim.snapTo(0f)
            if (dragState === state) {
                dragState = null
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(20.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "nav_config") {
                SettingsPreferenceGroup(
                    title = stringResource(R.string.main_screen_sections),
                    itemModifier = { index ->
                        // Material-style groups stack one Surface per row; the dragged
                        // tile itself must be raised, or dragging downward slides the
                        // row under the next tile that is painted after it.
                        if (configuredItems.getOrNull(index)?.item == draggedItem) {
                            Modifier.zIndex(4f)
                        } else {
                            Modifier
                        }
                    },
                ) {
                    configuredItems.forEachIndexed { index, config ->
                        item {
                            val isDragged = draggedItem == config.item
                            NavConfigPreferenceRow(
                                item = config,
                                canMoveUp = index > 0,
                                canMoveDown = index < configuredItems.lastIndex,
                                isDragging = isDragged,
                                shiftPx = rowShiftPx(config.item, index),
                                dragTranslation = if (isDragged && visualDragState != null) {
                                    { visualDragState.offsetAnim.value }
                                } else {
                                    null
                                },
                                onDragStart = { handleDragStart(config.item, index) },
                                onDrag = { deltaY -> handleDrag(deltaY) },
                                onDragEnd = { handleDragEnd() },
                                onDragCancel = { handleDragCancel() },
                                onBoundsChanged = { bounds ->
                                    rowCenters[config.item] = bounds.center.y
                                },
                                onMove = { direction -> onMoveItem(config.item, direction) },
                                onRemove = { onRemoveItem(config.item) },
                            )
                        }
                    }
                    if (canShowAddAction) {
                        item {
                            SettingsActionPreference(
                                title = stringResource(
                                    if (canAddAction) R.string.add else R.string.items_limit_exceeded,
                                ),
                                iconRes = R.drawable.ic_add,
                                enabled = canAddAction,
                                showChevron = false,
                                onClick = { isAddDialogVisible = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (isAddDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isAddDialogVisible = false },
            title = stringResource(R.string.add),
            text = {
                val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    item {
                        SettingsItemGroup(itemCount = availableItems.size) { index ->
                            val item = availableItems[index]
                            SettingsActionPreference(
                                title = stringResource(item.title),
                                iconRes = item.icon,
                                showChevron = false,
                                onClick = {
                                    onAddItem(item)
                                    isAddDialogVisible = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isAddDialogVisible = false },
                )
            },
        )
    }
}

private class NavRowDragState(
    val item: NavItem,
    val sourceIndex: Int,
) {
    val offsetAnim = Animatable(0f)

    /**
     * Monotonic finger travel in static layout coordinates, accumulated from the
     * raw drag deltas. Deliberately independent of [offsetAnim]: the visual
     * offset is clamped to slot centers, and re-deriving the finger from it
     * would form a feedback loop that traps the drag in the first slot.
     */
    var fingerCenter = Float.NaN
    var currentIndex by mutableIntStateOf(sourceIndex)
    var committedOrder: List<NavItem>? by mutableStateOf(null)
}

@Composable
private fun NavConfigPreferenceRow(
    item: NavItemConfigModel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    shiftPx: Float,
    dragTranslation: (() -> Float)?,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onMove: (direction: Int) -> Unit,
    onRemove: () -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentOnBoundsChanged by rememberUpdatedState(onBoundsChanged)
    val currentOnMove by rememberUpdatedState(onMove)
    val reorderLabel = stringResource(R.string.reorder)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    // Neighbours glide into place when the target slot changes; the dragged row
    // itself is driven one-to-one by [dragTranslation] instead.
    val settledShift by animateFloatAsState(
        targetValue = if (dragTranslation == null) shiftPx else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "nav_config_row_shift",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 4f else 0f)
            // Measured outside the layer so the reported bounds are always the
            // static layout position, never the transient drag offset.
            .onGloballyPositioned { coordinates ->
                currentOnBoundsChanged(coordinates.boundsInRoot())
            }
            .graphicsLayer {
                translationY = dragTranslation?.invoke() ?: settledShift
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = rememberSafePainter(item.item.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(item.item.title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.disabledHintResId != 0) {
                Text(
                    text = stringResource(item.disabledHintResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = reorderLabel
                    customActions = buildList {
                        if (canMoveUp) {
                            add(CustomAccessibilityAction(moveUpLabel) {
                                currentOnMove(-1)
                                true
                            })
                        }
                        if (canMoveDown) {
                            add(CustomAccessibilityAction(moveDownLabel) {
                                currentOnMove(1)
                                true
                            })
                        }
                    }
                }
                .pointerInput(item.item) {
                    detectDragGestures(
                        onDragStart = {
                            currentOnDragStart()
                        },
                        onDragCancel = {
                            currentOnDragCancel()
                        },
                        onDragEnd = {
                            currentOnDragEnd()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentOnDrag(dragAmount.y)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = if (isDragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.remove),
            )
        }
    }
}
