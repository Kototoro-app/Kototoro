package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import kotlin.math.ceil

/**
 * Which half of a double-page spread the logical page presents.
 *
 * Mirrors the legacy reader's crop-then-split order: crop (contentRect) is
 * applied first, then the split half is extracted from the cropped content.
 */
enum class TileSplit {
    NONE, LEFT, RIGHT,
}

/** Discriminates lattice tiles from whole-page overview tiles in a [TileKey]. */
enum class TileKind {
    /** Regular grid cell covering a slice of the page. */
    LATTICE,

    /** Whole-page base band decoded at a coarse sample size (LOD0 overview). */
    OVERVIEW,
}

/**
 * Stable identity of one decoded tile within the tile store.
 */
data class TileKey(
    val pageId: PageId,
    val kind: TileKind = TileKind.LATTICE,
    val sampleSize: Int,
    val col: Int,
    val row: Int,
)

/**
 * Decode + placement specification for a single tile.
 *
 * Geometry contract (plan 1.6):
 * - [logicalRect] is the exact topological placement in page-logical space.
 *   Destination rectangles tile the page without overlap; the renderer must
 *   crop gutter pixels away so adjacent tiles seam perfectly.
 * - [decodeRegion] is [logicalRect] mapped through crop/split/orientation into
 *   raw encoded space, then expanded by `seamPaddingPx * sampleSize` source
 *   pixels and clamped to the encoded bounds. The margin absorbs independent
 *   per-tile down-sampling phases so no black seams appear; it is measured in
 *   **decoded** pixels, so a coarse tile does not carry a level-zero margin.
 */
data class TileSpec(
    val key: TileKey,
    /** Placement in page-logical pixels (post split, post orientation). No gutter. */
    val logicalRect: IntRect,
    /** Raw encoded decode region including the sampling gutter. */
    val decodeRegion: IntRect,
    val sampleSize: Int,
) {
    /** Estimated decoded pixel dimensions (ceil of region / sampleSize). */
    val decodedSize: IntSize
        get() = IntSize(
            ceil(decodeRegion.width.toFloat() / sampleSize).toInt(),
            ceil(decodeRegion.height.toFloat() / sampleSize).toInt(),
        )

    /** Estimated ARGB_8888 resident cost of the decoded payload. */
    val estimatedBytes: Long
        get() = decodedSize.width.toLong() * decodedSize.height.toLong() * 4L
}

/**
 * Maps a logical page onto a lattice of region-decodable tiles.
 *
 * Pure geometry: zero Android or Compose dependencies. The mapping chain is
 *
 * ```
 * page-logical (what layout & renderer see)
 *   -- split offset --> content-logical
 *   -- [ImageSourceGeometry.mapLogicalToEncodedRegion] (crop + orientation) --> raw encoded
 *   -- gutter expand + clamp --> decodeRegion
 * ```
 *
 * @param tileDimension Tile cell size in **page-logical** pixels (callers derive
 *   it from [TilePolicy] / [DecodePlan.Tiled], adjusting for split halves).
 * @param sampleSize Power-of-two sample size applied to this grid's LOD.
 * @param outputGutterPx Screen-space gutter that expands tile *queries*, so the
 *   tile next to the viewport stays warm. Measured in page-logical pixels.
 * @param seamPaddingPx Padding added to each tile's [TileSpec.decodeRegion]
 *   against sampling seams, measured in **decoded** pixels per side (multiplied
 *   by [sampleSize] to convert into encoded pixels).
 */
class TileGrid(
    val pageId: PageId,
    val geometry: ImageSourceGeometry,
    val split: TileSplit = TileSplit.NONE,
    val tileDimension: IntSize,
    val sampleSize: Int = 1,
    val outputGutterPx: Int = DEFAULT_OUTPUT_GUTTER_PX,
    val seamPaddingPx: Int = DEFAULT_SEAM_PADDING_PX,
) {
    init {
        require(sampleSize >= 1 && (sampleSize and (sampleSize - 1)) == 0) {
            "sampleSize must be a positive power of 2: $sampleSize"
        }
        require(outputGutterPx >= 0) { "outputGutterPx must be >= 0: $outputGutterPx" }
        require(seamPaddingPx >= 0) { "seamPaddingPx must be >= 0: $seamPaddingPx" }
        require(tileDimension.width > 0) { "tileDimension.width must be > 0" }
        require(tileDimension.height > 0) { "tileDimension.height must be > 0" }
    }

    /** Logical size of the full (unsplit) content after crop and orientation. */
    val contentLogicalSize: IntSize = geometry.logicalSize

    private val splitOriginX: Int = when (split) {
        TileSplit.NONE -> 0
        TileSplit.LEFT -> 0
        TileSplit.RIGHT -> contentLogicalSize.width - halfWidth(contentLogicalSize.width)
    }

    /** Logical size of the page as presented (post split). */
    val pageSize: IntSize = when (split) {
        TileSplit.NONE -> contentLogicalSize
        else -> IntSize(halfWidth(contentLogicalSize.width), contentLogicalSize.height)
    }

    /** Page-logical rectangle covering the presented half. */
    val pageBounds: IntRect = IntRect.fromLtwh(0, 0, pageSize.width, pageSize.height)

    val columns: Int = if (pageSize.width > 0) ceil(pageSize.width / tileDimension.width.toFloat()).toInt() else 0
    val rows: Int = if (pageSize.height > 0) ceil(pageSize.height / tileDimension.height.toFloat()).toInt() else 0
    val tileCount: Int get() = columns * rows

    /** Returns the lattice tile at [col]/[row], or null when out of bounds. */
    fun tileAt(col: Int, row: Int): TileSpec? {
        if (col !in 0 until columns || row !in 0 until rows) return null
        val cell = IntRect.fromLtwh(
            left = col * tileDimension.width,
            top = row * tileDimension.height,
            width = tileDimension.width,
            height = tileDimension.height,
        )
        val logicalRect = cell.intersectionOrNull(pageBounds) ?: return null
        if (logicalRect.isEmpty) return null
        return buildSpec(TileKind.LATTICE, col, row, logicalRect)
    }

    /** Returns the lattice tile identified by [key], or null when the key is not part of this grid. */
    fun tileOf(key: TileKey): TileSpec? {
        if (key.pageId != pageId || key.kind != TileKind.LATTICE || key.sampleSize != sampleSize) return null
        return tileAt(key.col, key.row)
    }

    /**
     * Returns all lattice tiles intersecting [logicalRegion] after expanding it
     * by [outputGutterPx] (prefetch halo), ordered top-to-bottom, left-to-right.
     * An empty region produces no tiles.
     */
    fun tilesIntersecting(logicalRegion: IntRect): List<TileSpec> {
        if (logicalRegion.isEmpty) return emptyList()
        val expanded = logicalRegion.expand(outputGutterPx).intersectionOrNull(pageBounds) ?: return emptyList()
        val firstCol = (expanded.left / tileDimension.width).coerceIn(0, (columns - 1).coerceAtLeast(0))
        val lastCol = ((expanded.right - 1) / tileDimension.width).coerceIn(0, (columns - 1).coerceAtLeast(0))
        val firstRow = (expanded.top / tileDimension.height).coerceIn(0, (rows - 1).coerceAtLeast(0))
        val lastRow = ((expanded.bottom - 1) / tileDimension.height).coerceIn(0, (rows - 1).coerceAtLeast(0))
        val result = ArrayList<TileSpec>()
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) {
                tileAt(col, row)?.let(result::add)
            }
        }
        return result
    }

    /** Returns every lattice tile of the page in reading (top-to-bottom) order. */
    fun allTiles(): List<TileSpec> {
        val result = ArrayList<TileSpec>(tileCount)
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                tileAt(col, row)?.let(result::add)
            }
        }
        return result
    }

    /**
     * Builds the whole-page overview tile (LOD0 base band) at [overviewSampleSize].
     * The overview's key uses [TileKind.OVERVIEW] so it never collides with lattice keys.
     */
    fun overviewTile(overviewSampleSize: Int): TileSpec {
        require(overviewSampleSize >= 1 && (overviewSampleSize and (overviewSampleSize - 1)) == 0) {
            "overviewSampleSize must be a positive power of 2: $overviewSampleSize"
        }
        require(!pageSize.isEmpty) { "page must not be empty: $pageSize" }
        return buildSpec(TileKind.OVERVIEW, col = 0, row = 0, logicalRect = pageBounds, specSampleSize = overviewSampleSize)
    }

    private fun buildSpec(
        kind: TileKind,
        col: Int,
        row: Int,
        logicalRect: IntRect,
        specSampleSize: Int = sampleSize,
    ): TileSpec {
        val contentLogical = logicalRect.translate(splitOriginX, 0)
        val encoded = geometry.mapLogicalToEncodedRegion(contentLogical)
        val sourceGutter = seamPaddingPx * specSampleSize
        val clamped = encoded
            .expand(sourceGutter)
            .intersectionOrNull(IntRect.fromLtwh(0, 0, geometry.encodedSize.width, geometry.encodedSize.height))
            ?: encoded
        return TileSpec(
            key = TileKey(
                pageId = pageId,
                kind = kind,
                sampleSize = specSampleSize,
                col = col,
                row = row,
            ),
            logicalRect = logicalRect,
            decodeRegion = clamped,
            sampleSize = specSampleSize,
        )
    }

    private fun IntRect.expand(delta: Int): IntRect {
        if (delta == 0) return this
        return IntRect(left - delta, top - delta, right + delta, bottom + delta)
    }

    private fun halfWidth(width: Int): Int = if (width < 2) width else width / 2

    companion object {
        /**
         * Default query halo: expands the visible region before it selects tiles, so the
         * tile just outside the viewport is decoded before it is needed.
         */
        const val DEFAULT_OUTPUT_GUTTER_PX = 128

        /**
         * Default seam padding per tile side, in **decoded** pixels.
         *
         * Independent of the level by construction (`seamPaddingPx * sampleSize` encoded pixels,
         * divided back by `sampleSize` when decoding). Sized by measurement: sharing the 128 px query
         * halo here made a level-2 tile 75% padding and a level-0 tile 56%, i.e. every level paid for
         * a level-zero margin.
         */
        const val DEFAULT_SEAM_PADDING_PX = 8
    }
}
