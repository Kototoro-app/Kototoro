package org.skepsun.kototoro.reader.core

/**
 * Behavior governing whether a page can be combined into a multi-page spread (e.g. double-page).
 */
enum class SpreadBehavior {
    /** Automatically determined by page aspect ratio and wide page policy. */
    AUTO,
    /** Forces the page to occupy an isolated slot alone (e.g. cover, wide page, chapter transition). */
    SOLO,
    /** Explicitly allows pairing with an adjacent page. */
    PAIRABLE,
}

/**
 * Sub-segment of a logical page (useful when wide pages are pre-split into dual logical pages).
 */
enum class PageSegment {
    FULL,
    LEFT_HALF,
    RIGHT_HALF,
}

/**
 * Structural specification for a page in a paged reader scene.
 *
 * Preserves chapter metadata and spread constraints for multi-page spread resolution
 * without polluting the general [PageGeometryHint].
 */
data class PagedPageSpec(
    val pageId: PageId,
    val geometryHint: PageGeometryHint,
    val chapterId: Long,
    val chapterPageIndex: Int,
    val segment: PageSegment = PageSegment.FULL,
    val spreadBehavior: SpreadBehavior = SpreadBehavior.AUTO,
)
