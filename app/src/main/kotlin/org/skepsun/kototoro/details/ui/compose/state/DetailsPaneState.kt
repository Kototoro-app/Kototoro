package org.skepsun.kototoro.details.ui.compose.state

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState as createAnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class CompactDetailsPaneAnchor {
    Collapsed,
    Hovered,
    Full,
}

@OptIn(ExperimentalFoundationApi::class)
@Stable
class DetailsPaneState internal constructor(
    private val density: Density,
    private val coroutineScope: CoroutineScope,
    val collapsedHeight: Dp,
    val paneHeight: Dp,
    val hoveredHeight: Dp,
    initialPageGridSizeValue: Float,
    initialPageThumbnailAspectRatio: Float,
    initialSelectedTabId: Int,
    initialChapterQuery: String = "",
    initialAnchor: CompactDetailsPaneAnchor = CompactDetailsPaneAnchor.Collapsed,
) {

    val anchoredState = createAnchoredDraggableState(
        initialValue = initialAnchor,
        positionalThreshold = { distance -> distance * 0.35f },
        velocityThreshold = { Float.POSITIVE_INFINITY },
        snapAnimationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        decayAnimationSpec = exponentialDecay(),
        confirmValueChange = { true },
    )

    var anchor by mutableStateOf(initialAnchor)
        private set

    var isGridSizeControlsVisible by mutableStateOf(false)
        private set

    var isChapterSearchVisible by mutableStateOf(false)
        private set

    var chapterSelectionState by mutableStateOf<ChapterSelectionUiState?>(null)
        private set

    var pageGridSizeValue by mutableFloatStateOf(initialPageGridSizeValue)
        private set

    var pageThumbnailAspectRatio by mutableFloatStateOf(initialPageThumbnailAspectRatio)
        private set

    var selectedTabId by mutableIntStateOf(initialSelectedTabId)
        private set

    var chapterQuery by mutableStateOf(initialChapterQuery)
        private set

    private var hostHeightPx by mutableFloatStateOf(0f)
    private var isNestedPaneDragInProgress = false

    private val paneHeightPx: Float
        get() = with(density) { paneHeight.toPx() }

    private val collapsedHeightPx: Float
        get() = with(density) { collapsedHeight.toPx() }

    private val hoveredHeightPx: Float
        get() = with(density) { hoveredHeight.toPx() }

    val fullOffsetPx: Float
        get() = compactPaneFullOffsetPx(
            hostHeightPx = hostHeightPx,
            paneHeightPx = paneHeightPx,
        )

    val collapsedOffsetPx: Float
        get() = compactPaneCollapsedOffsetPx(
            hostHeightPx = hostHeightPx,
            collapsedHeightPx = collapsedHeightPx,
        )

    val hoveredOffsetPx: Float
        get() = compactPaneHoveredOffsetPx(
            hostHeightPx = hostHeightPx,
            hoveredHeightPx = hoveredHeightPx,
            fullOffset = fullOffsetPx,
            collapsedOffset = collapsedOffsetPx,
        )

    val halfOffsetPx: Float
        get() = (fullOffsetPx + collapsedOffsetPx) / 2f

    val translationY: Float
        get() = anchoredState.offset.takeIf { it.isFinite() } ?: compactPaneOffsetForAnchor(
            anchor = anchor,
            fullOffset = fullOffsetPx,
            hoveredOffset = hoveredOffsetPx,
            collapsedOffset = collapsedOffsetPx,
        )

    val expansionProgress: Float
        get() {
            val currentOffset = anchoredState.offset.takeIf { it.isFinite() } ?: compactPaneOffsetForAnchor(
                anchor = anchor,
                fullOffset = fullOffsetPx,
                hoveredOffset = hoveredOffsetPx,
                collapsedOffset = collapsedOffsetPx,
            )
            val travelDistance = (collapsedOffsetPx - fullOffsetPx).coerceAtLeast(1f)
            return ((collapsedOffsetPx - currentOffset) / travelDistance).coerceIn(0f, 1f)
        }

    val isFullyExpanded: Boolean
        get() = anchor == CompactDetailsPaneAnchor.Full

    val shouldHandleBack: Boolean
        get() = chapterSelectionState != null ||
            isGridSizeControlsVisible ||
            isChapterSearchVisible ||
            anchor != CompactDetailsPaneAnchor.Collapsed

    fun onHostHeightChanged(heightPx: Float) {
        if (hostHeightPx == heightPx) return
        hostHeightPx = heightPx
        updateAnchors(anchor)
    }

    fun onSettledValueChanged() {
        anchor = anchoredState.settledValue
    }

    fun showGridSizeControls() {
        isGridSizeControlsVisible = true
    }

    fun hideGridSizeControls() {
        isGridSizeControlsVisible = false
    }

    fun onChapterSelectionStateChanged(state: ChapterSelectionUiState?) {
        chapterSelectionState = state
    }

    fun syncPageGridSizeValue(value: Float) {
        pageGridSizeValue = value
    }

    fun syncPageThumbnailAspectRatio(value: Float) {
        pageThumbnailAspectRatio = value
    }

    fun updatePageGridSizeValue(
        value: Float,
        onPersist: (Float) -> Unit = {},
    ) {
        pageGridSizeValue = value
        onPersist(value)
    }

    fun updatePageThumbnailAspectRatio(
        value: Float,
        onPersist: (Float) -> Unit = {},
    ) {
        pageThumbnailAspectRatio = value
        onPersist(value)
    }

    fun selectTab(
        requestedTabId: Int,
        availableTabIds: List<Int>,
        onPersist: (Int) -> Unit = {},
    ) {
        val resolvedTabId = resolveSelectedTabId(requestedTabId, availableTabIds)
        selectedTabId = resolvedTabId
        onPersist(resolvedTabId)
    }

    fun syncSelectedTabs(
        availableTabIds: List<Int>,
        defaultTabId: Int,
        onDefaultResolved: (Int) -> Unit = {},
    ) {
        val resolvedDefaultTabId = resolveSelectedTabId(defaultTabId, availableTabIds)
        if (resolvedDefaultTabId != defaultTabId) {
            onDefaultResolved(resolvedDefaultTabId)
        }
        selectedTabId = resolveSelectedTabId(selectedTabId, availableTabIds)
    }

    fun resolvedSelectedTabId(availableTabIds: List<Int>): Int {
        return resolveSelectedTabId(selectedTabId, availableTabIds)
    }

    fun updateChapterQuery(
        query: String,
        onSearch: (String?) -> Unit = {},
    ) {
        chapterQuery = query
        onSearch(query.ifBlank { null })
    }

    fun clearChapterQuery(
        onCleared: () -> Unit = {},
    ) {
        if (chapterQuery.isEmpty()) return
        chapterQuery = ""
        onCleared()
    }

    fun toggleChapterSearch(onClosed: () -> Unit = {}) {
        if (isChapterSearchVisible) {
            hideChapterSearch(onClosed)
        } else {
            isChapterSearchVisible = true
        }
    }

    fun hideChapterSearch(onClosed: () -> Unit = {}) {
        if (!isChapterSearchVisible) return
        isChapterSearchVisible = false
        onClosed()
    }

    fun syncTopBarContext(
        selectedTabId: Int,
        chaptersTabId: Int,
        isSheetFullyExpanded: Boolean,
    ) {
        if (!isSheetFullyExpanded || selectedTabId == chaptersTabId) {
            hideGridSizeControls()
        }
    }

    fun syncChapterSearchContext(
        selectedTabId: Int,
        chaptersTabId: Int,
        isSheetFullyExpanded: Boolean,
        onClosed: () -> Unit = {},
    ) {
        if (!isSheetFullyExpanded || selectedTabId != chaptersTabId) {
            hideChapterSearch(onClosed)
        }
    }

    internal fun topBarMode(
        selectedTabId: Int,
        chaptersTabId: Int,
        isCompactLayout: Boolean,
    ): DetailsPaneTopBarMode {
        return when {
            isGridSizeControlsVisible -> DetailsPaneTopBarMode.GridSizeControls
            chapterSelectionState != null && selectedTabId == chaptersTabId -> DetailsPaneTopBarMode.ChapterSelection
            selectedTabId == chaptersTabId && (anchor == CompactDetailsPaneAnchor.Full || !isCompactLayout) -> DetailsPaneTopBarMode.ExpandedChapterTools
            anchor == CompactDetailsPaneAnchor.Full || !isCompactLayout -> DetailsPaneTopBarMode.ExpandedGridTools
            else -> DetailsPaneTopBarMode.CollapsedReadDock
        }
    }

    fun animateTo(targetAnchor: CompactDetailsPaneAnchor) {
        coroutineScope.launch {
            updateAnchors(targetAnchor)
            anchoredState.animateTo(
                targetAnchor,
                animationSpec = tween(
                    durationMillis = compactPaneAnimationDurationMillis(targetAnchor),
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    fun onChapterSelectionActivated() {
        if (anchor != CompactDetailsPaneAnchor.Full) {
            animateTo(CompactDetailsPaneAnchor.Full)
        }
    }

    fun onOpenPaneRequested() {
        animateTo(
            if (anchor == CompactDetailsPaneAnchor.Full) {
                CompactDetailsPaneAnchor.Full
            } else {
                CompactDetailsPaneAnchor.Hovered
            },
        )
    }

    fun handleBack(
        onBackClick: () -> Unit,
        onChapterSearchClosed: () -> Unit = {},
    ) {
        chapterSelectionState?.let { selectionState ->
            selectionState.onClearSelection()
            return
        }
        if (isGridSizeControlsVisible) {
            hideGridSizeControls()
            return
        }
        if (isChapterSearchVisible) {
            hideChapterSearch(onChapterSearchClosed)
            return
        }
        when (anchor) {
            CompactDetailsPaneAnchor.Full,
            CompactDetailsPaneAnchor.Hovered -> animateTo(CompactDetailsPaneAnchor.Collapsed)
            CompactDetailsPaneAnchor.Collapsed -> onBackClick()
        }
    }

    fun dispatchPaneDragDelta(deltaY: Float): Float {
        val consumedDelta = anchoredState.dispatchRawDelta(deltaY)
        if (consumedDelta != 0f) {
            isNestedPaneDragInProgress = true
        }
        return consumedDelta
    }

    fun hasNestedPaneDragInProgress(): Boolean = isNestedPaneDragInProgress

    suspend fun settleAfterNestedDrag(velocityY: Float = 0f) {
        if (!isNestedPaneDragInProgress) return
        isNestedPaneDragInProgress = false
        anchoredState.settle(velocityY)
    }

    private fun updateAnchors(targetAnchor: CompactDetailsPaneAnchor = anchor) {
        anchoredState.updateAnchors(
            DraggableAnchors {
                CompactDetailsPaneAnchor.Full at fullOffsetPx
                CompactDetailsPaneAnchor.Hovered at hoveredOffsetPx
                CompactDetailsPaneAnchor.Collapsed at collapsedOffsetPx
            },
            targetAnchor,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberDetailsPaneState(
    screenHeightDp: Int,
    collapsedHeight: Dp = 96.dp,
    initialPageGridSizeValue: Float,
    initialPageThumbnailAspectRatio: Float,
    initialSelectedTabId: Int,
    initialChapterQuery: String = "",
    initialAnchor: CompactDetailsPaneAnchor = CompactDetailsPaneAnchor.Collapsed,
): DetailsPaneState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val paneHeight = remember(screenHeightDp) {
        (screenHeightDp.dp + 32.dp).coerceAtLeast(520.dp)
    }
    val hoveredHeight = remember(screenHeightDp, paneHeight) {
        (screenHeightDp.dp * 0.52f)
            .coerceAtLeast(360.dp)
            .coerceAtMost(paneHeight - 96.dp)
    }
    val state = remember(
        density,
        coroutineScope,
        collapsedHeight,
        paneHeight,
        hoveredHeight,
        initialAnchor,
    ) {
        DetailsPaneState(
            density = density,
            coroutineScope = coroutineScope,
            collapsedHeight = collapsedHeight,
            paneHeight = paneHeight,
            hoveredHeight = hoveredHeight,
            initialPageGridSizeValue = initialPageGridSizeValue,
            initialPageThumbnailAspectRatio = initialPageThumbnailAspectRatio,
            initialSelectedTabId = initialSelectedTabId,
            initialChapterQuery = initialChapterQuery,
            initialAnchor = initialAnchor,
        )
    }
    LaunchedEffect(state.anchoredState.settledValue) {
        state.onSettledValueChanged()
    }
    return state
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberDetailsPaneNestedScrollConnection(
    state: DetailsPaneState?,
    canChildScrollBackward: () -> Boolean,
): NestedScrollConnection? {
    if (state == null) return null
    return remember(state, canChildScrollBackward) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val hasGestureOwnership = state.hasNestedPaneDragInProgress()
                val shouldStartPaneDrag = when (state.anchor) {
                    CompactDetailsPaneAnchor.Hovered,
                    CompactDetailsPaneAnchor.Collapsed -> available.y != 0f
                    CompactDetailsPaneAnchor.Full -> available.y > 0f && !canChildScrollBackward()
                }
                if (!hasGestureOwnership && !shouldStartPaneDrag) return Offset.Zero
                val consumedY = state.dispatchPaneDragDelta(available.y)
                return Offset(
                    x = 0f,
                    y = if (hasGestureOwnership && consumedY == 0f) available.y else consumedY,
                )
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.hasNestedPaneDragInProgress()) {
                    return Velocity.Zero
                }
                state.settleAfterNestedDrag(velocityY = available.y)
                return Velocity(x = 0f, y = available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!state.hasNestedPaneDragInProgress()) {
                    return Velocity.Zero
                }
                val settleVelocity = if (available.y != 0f) available.y else consumed.y
                state.settleAfterNestedDrag(velocityY = settleVelocity)
                return Velocity(x = 0f, y = settleVelocity)
            }
        }
    }
}

private fun compactPaneFullOffsetPx(
    hostHeightPx: Float,
    paneHeightPx: Float,
): Float {
    if (hostHeightPx <= 0f) return 0f
    return (hostHeightPx - paneHeightPx).coerceAtLeast(0f)
}

private fun compactPaneCollapsedOffsetPx(
    hostHeightPx: Float,
    collapsedHeightPx: Float,
): Float {
    if (hostHeightPx <= 0f) return 0f
    return (hostHeightPx - collapsedHeightPx).coerceAtLeast(0f)
}

private fun compactPaneHoveredOffsetPx(
    hostHeightPx: Float,
    hoveredHeightPx: Float,
    fullOffset: Float,
    collapsedOffset: Float,
): Float {
    if (hostHeightPx <= 0f || collapsedOffset <= fullOffset) {
        return fullOffset
    }
    val rawOffset = (hostHeightPx - hoveredHeightPx).coerceAtLeast(0f)
    val edgePadding = minOf(28f, (collapsedOffset - fullOffset) / 3f)
    return rawOffset.coerceIn(fullOffset + edgePadding, collapsedOffset - edgePadding)
}

private fun compactPaneOffsetForAnchor(
    anchor: CompactDetailsPaneAnchor,
    fullOffset: Float,
    hoveredOffset: Float,
    collapsedOffset: Float,
): Float {
    return when (anchor) {
        CompactDetailsPaneAnchor.Collapsed -> collapsedOffset
        CompactDetailsPaneAnchor.Hovered -> hoveredOffset
        CompactDetailsPaneAnchor.Full -> fullOffset
    }
}

private fun compactPaneAnimationDurationMillis(
    anchor: CompactDetailsPaneAnchor,
): Int {
    return when (anchor) {
        CompactDetailsPaneAnchor.Collapsed -> 420
        CompactDetailsPaneAnchor.Hovered -> 440
        CompactDetailsPaneAnchor.Full -> 480
    }
}

private fun resolveSelectedTabId(
    requestedTabId: Int,
    availableTabIds: List<Int>,
): Int {
    return if (requestedTabId in availableTabIds) {
        requestedTabId
    } else {
        when {
            requestedTabId > 0 -> {
                availableTabIds.getOrElse((requestedTabId - 1).coerceAtLeast(0)) { availableTabIds.first() }
            }
            else -> availableTabIds.first()
        }
    }
}
