package org.skepsun.kototoro.reader.core

/**
 * Reading semantics resolved from the same visible frame that is consumed by a renderer.
 *
 * Keeping these values on [ReaderFrame] makes a single scene resolution the source of truth for
 * both rendering and persisted progress.
 */
data class ReaderProgressSnapshot(
    val firstVisiblePageId: PageId?,
    val lastVisiblePageId: PageId?,
    val activePageId: PageId?,
    val intraPageOffsetPx: Float,
) {
    /** Backward-compatible alias for vertical reading lower page. */
    val lowerPageId: PageId? get() = firstVisiblePageId

    /** Backward-compatible alias for vertical reading upper page. */
    val upperPageId: PageId? get() = lastVisiblePageId

    constructor(
        lowerPageId: PageId?,
        upperPageId: PageId?,
        activePageId: PageId?,
        intraPageOffsetPx: Float,
        @Suppress("UNUSED_PARAMETER") marker: Unit = Unit,
    ) : this(
        firstVisiblePageId = lowerPageId,
        lastVisiblePageId = upperPageId,
        activePageId = activePageId,
        intraPageOffsetPx = intraPageOffsetPx,
    )

    companion object {
        val Empty = ReaderProgressSnapshot(
            firstVisiblePageId = null,
            lastVisiblePageId = null,
            activePageId = null,
            intraPageOffsetPx = 0f,
        )

        internal fun from(
            viewport: ReaderViewport,
            visibleNodes: List<VisibleNode>,
            direction: SceneReadingDirection = SceneReadingDirection.TOP_TO_BOTTOM,
        ): ReaderProgressSnapshot {
            val first = visibleNodes.firstOrNull() ?: return Empty
            val intraPageOffset = when (direction) {
                SceneReadingDirection.TOP_TO_BOTTOM -> {
                    (viewport.bounds.top - first.sceneBounds.top).coerceAtLeast(0f)
                }
                SceneReadingDirection.LEFT_TO_RIGHT -> {
                    (viewport.bounds.left - first.sceneBounds.left).coerceAtLeast(0f)
                }
                SceneReadingDirection.RIGHT_TO_LEFT -> {
                    (first.sceneBounds.right - viewport.bounds.right).coerceAtLeast(0f)
                }
            }
            return ReaderProgressSnapshot(
                firstVisiblePageId = first.pageId,
                lastVisiblePageId = visibleNodes.last().pageId,
                activePageId = first.pageId,
                intraPageOffsetPx = intraPageOffset,
            )
        }
    }
}
