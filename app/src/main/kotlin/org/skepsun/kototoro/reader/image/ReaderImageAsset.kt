package org.skepsun.kototoro.reader.image

import android.graphics.Bitmap
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
     * Tiled presentation asset backed by a [TileGrid] and queried through a [TileStore].
     *
     * Used for ultra-long webtoon strips or images that exceed maximum hardware/memory limits.
     * Optionally points to an [overviewKey] representing the coarse LOD0 whole-page base band.
     */
    data class Tiled(
        override val pageId: PageId,
        val grid: TileGrid,
        val tileStore: TileStore,
        val overviewKey: TileKey? = null,
    ) : ReaderImageAsset
}
