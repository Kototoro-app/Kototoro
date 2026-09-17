package org.skepsun.kototoro.reader.core

/**
 * Reading direction / axis of a reader scene.
 */
enum class SceneReadingDirection {
    TOP_TO_BOTTOM,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT;

    val isVertical: Boolean get() = this == TOP_TO_BOTTOM
    val isHorizontal: Boolean get() = !isVertical

    /**
     * Sign multiplier applied to physical drag/scroll velocity along the primary axis
     * such that forward reading velocity is strictly positive.
     */
    val directionSign: Float
        get() = when (this) {
            TOP_TO_BOTTOM -> 1.0f
            LEFT_TO_RIGHT -> 1.0f
            RIGHT_TO_LEFT -> -1.0f
        }
}

/**
 * Result of an anchored layout compensation when page dimensions change.
 *
 * @property deltaX The change in horizontal scroll offset required to keep the active view visually fixed.
 * @property deltaY The change in vertical scroll offset required to keep the active view visually fixed.
 * @property compensatedViewport The adjusted viewport matching the new scene layout.
 */
data class AnchorCompensation(
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val compensatedViewport: ReaderViewport,
) {
    constructor(deltaY: Float, compensatedViewport: ReaderViewport) : this(0f, deltaY, compensatedViewport)
}

/**
 * Platform-agnostic, geometry-only contract for continuous 2D reader scenes.
 *
 * Conforms to ADR 0002 Constraint 1:
 * "ReaderScene contains zero Android dependencies and decouples scene layout from image decoding status."
 */
interface ReaderScene {
    /** Reading direction of this scene. */
    val readingDirection: SceneReadingDirection get() = SceneReadingDirection.TOP_TO_BOTTOM

    /** Total extent (length in pixels) of the scene along its primary reading axis. */
    val totalSceneExtent: Float

    /** Ordered list of laid-out page geometries in canonical reading order. */
    val pageGeometries: List<PageGeometry>

    /** Total number of pages in the scene. */
    val pageCount: Int

    /** Monotonic geometry revision used to invalidate derived resource windows and render frames. */
    val revision: Long

    /**
     * Returns the 0-indexed position of [pageId] in the scene, or -1 if absent.
     */
    fun indexOf(pageId: PageId): Int

    /**
     * Computes the visible nodes and active reading semantic state for a given [viewport].
     *
     * Guaranteed contract:
     * - Nodes in [ReaderFrame.visibleNodes] MUST be ordered in canonical reading order.
     */
    fun resolve(viewport: ReaderViewport): ReaderFrame

    /**
     * Resolves the active reading page ID for a given [viewport].
     */
    fun resolveActivePageId(viewport: ReaderViewport): PageId? = resolve(viewport).progress.activePageId

    /**
     * Resolves the coordinate of [pageId] along the primary reading axis in scene coordinates, or null if absent.
     */
    fun resolvePageScrollPosition(pageId: PageId): Float?
}

/**
 * Mutable extension of [ReaderScene] allowing dynamic page mutations and anchored layout compensation.
 */
interface MutableReaderScene : ReaderScene {
    /**
     * Fully replaces the current pages with a new page list.
     */
    fun setPages(pages: List<Pair<PageId, PageGeometryHint>>)

    /**
     * Updates the page list in the scene, preserving exact geometry hints for pages
     * that already have them, and computing an [AnchorCompensation] to prevent perceptual jumps.
     */
    fun updatePages(
        newPages: List<Pair<PageId, PageGeometryHint>>,
        currentViewport: ReaderViewport? = null,
    ): AnchorCompensation?

    /**
     * Updates the geometry hint for a specific page.
     *
     * If [currentViewport] is provided, computes an [AnchorCompensation] preserving the visual reading position
     * without any perceptual jump (Zero CLS with Anchored Correction).
     */
    fun updatePageHint(
        pageId: PageId,
        newHint: PageGeometryHint,
        currentViewport: ReaderViewport? = null,
    ): AnchorCompensation?
}
