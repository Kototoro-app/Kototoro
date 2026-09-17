package org.skepsun.kototoro.reader.core

/**
 * Reading semantics resolved from the same visible frame that is consumed by a renderer.
 *
 * Keeping these values on [ReaderFrame] makes a single scene resolution the source of truth for
 * both rendering and persisted progress.
 */
data class ReaderProgressSnapshot(
    val lowerPageId: PageId?,
    val upperPageId: PageId?,
    val activePageId: PageId?,
    val intraPageOffsetPx: Float,
) {
    companion object {
        val Empty = ReaderProgressSnapshot(
            lowerPageId = null,
            upperPageId = null,
            activePageId = null,
            intraPageOffsetPx = 0f,
        )

        internal fun from(
            viewport: ReaderViewport,
            visibleNodes: List<VisibleNode>,
        ): ReaderProgressSnapshot {
            val first = visibleNodes.firstOrNull() ?: return Empty
            return ReaderProgressSnapshot(
                lowerPageId = first.pageId,
                upperPageId = visibleNodes.last().pageId,
                activePageId = first.pageId,
                intraPageOffsetPx = (viewport.bounds.top - first.sceneBounds.top).coerceAtLeast(0f),
            )
        }
    }
}
