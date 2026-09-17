package org.skepsun.kototoro.reader.image

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import org.skepsun.kototoro.reader.core.PageId

/**
 * Polymorphic presentation asset representing loaded/decoded page content.
 *
 * Conforms to ADR 0002 Constraint 4:
 * "Assets are handle-based (ReaderImageAsset), not hardcoded to in-memory Bitmap."
 */
sealed interface ReaderImageAsset {
    val pageId: PageId

    /**
     * Raw encoded file or stream handle. Can be consumed by native decoders or WebGPU texture upload.
     */
    data class Encoded(
        override val pageId: PageId,
        val uriString: String,
        val mimeType: String? = null,
    ) : ReaderImageAsset

    /**
     * Provisional preview asset shown on slow networks while full original is downloading.
     * Does NOT satisfy [isAuthoritativePresentation] and does not alter scene geometry.
     */
    data class Preview(
        override val pageId: PageId,
        val imageBitmap: ImageBitmap,
    ) : ReaderImageAsset

    /**
     * Standard Android Bitmap asset for View Canvas or legacy views.
     */
    data class AndroidBitmap(
        override val pageId: PageId,
        val bitmap: Bitmap,
    ) : ReaderImageAsset

    /**
     * Compose ImageBitmap asset optimized for DrawScope.drawImage.
     */
    data class ComposeImage(
        override val pageId: PageId,
        val imageBitmap: ImageBitmap,
    ) : ReaderImageAsset

    /**
     * Animated drawable asset (GIF, Animated WebP, Animated AVIF) preserving multi-frame playback.
     */
    data class Animated(
        override val pageId: PageId,
        val drawable: Drawable,
        val width: Int,
        val height: Int,
    ) : ReaderImageAsset

    /**
     * Represents a single LOD lattice and base overview band for tiled rendering.
     */
    data class TileLayer(
        val grid: TileGrid,
        val sampleSize: Int = grid.sampleSize,
        val overviewKey: TileKey? = null,
    )

    /**
     * Tiled presentation asset backed by a [TileGrid] and queried through a [TileStore].
     *
     * Supports multi-LOD coexistence: [base] is always fully rendered, while [target]
     * progressively overlays sharper tiles as they arrive, eliminating white/flickering flashes.
     */
    data class Tiled(
        override val pageId: PageId,
        val base: TileLayer,
        val target: TileLayer? = null,
        val tileStore: TileStore,
    ) : ReaderImageAsset {
        constructor(
            pageId: PageId,
            grid: TileGrid,
            tileStore: TileStore,
            overviewKey: TileKey? = null,
        ) : this(
            pageId = pageId,
            base = TileLayer(grid, grid.sampleSize, overviewKey),
            target = null,
            tileStore = tileStore,
        )

        val grid: TileGrid get() = base.grid
        val overviewKey: TileKey? get() = base.overviewKey
    }
}

/**
 * Returns true if this asset is a fully decoded authoritative presentation asset
 * (i.e. not an encoded placeholder or provisional low-res preview).
 */
val ReaderImageAsset.isAuthoritativePresentation: Boolean
    get() = this is ReaderImageAsset.ComposeImage ||
        this is ReaderImageAsset.AndroidBitmap ||
        this is ReaderImageAsset.Animated ||
        this is ReaderImageAsset.Tiled
