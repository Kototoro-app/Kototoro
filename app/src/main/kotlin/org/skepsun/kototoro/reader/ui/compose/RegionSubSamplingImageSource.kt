package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import java.io.IOException

/** Exposes a logical subsection while keeping tile decoding backed by the original image. */
internal class RegionSubSamplingImageSource(
    private val delegate: SubSamplingImageSource,
    private val resolveRegion: (IntSize) -> IntRect,
) : SubSamplingImageSource {

    override val preview: ImageBitmap? = null

    override suspend fun decoder(): ImageRegionDecoder.Factory {
        val delegateFactory = delegate.decoder()
        return ImageRegionDecoder.Factory { params ->
            val decoder = delegateFactory.create(params)
            try {
                RegionImageRegionDecoder(decoder, resolveRegion(decoder.imageSize))
            } catch (error: Throwable) {
                decoder.close()
                throw error
            }
        }
    }

    override fun close() = delegate.close()
}

private class RegionImageRegionDecoder(
    private val delegate: ImageRegionDecoder,
    private val region: IntRect,
) : ImageRegionDecoder {

    init {
        if (region.width <= 0 || region.height <= 0 ||
            region.left < 0 || region.top < 0 ||
            region.right > delegate.imageSize.width || region.bottom > delegate.imageSize.height
        ) {
            throw IOException("Invalid image region $region for ${delegate.imageSize}")
        }
    }

    override val imageSize: IntSize = region.size

    override suspend fun decodeRegion(
        region: IntRect,
        sampleSize: Int,
    ): ImageRegionDecoder.DecodeResult {
        return delegate.decodeRegion(region.translate(this.region.topLeft), sampleSize)
    }

    override fun close() = delegate.close()
}

internal fun SubSamplingImageSource.withRegion(
    cropBounds: IntRect?,
    split: ReaderPageSplit,
): SubSamplingImageSource {
    if (cropBounds == null && split == ReaderPageSplit.NONE) return this
    return RegionSubSamplingImageSource(this) { imageSize -> imageRegion(imageSize, cropBounds, split) }
}

internal fun pageSplitRegion(imageSize: IntSize, split: ReaderPageSplit): IntRect {
    return imageRegion(imageSize, cropBounds = null, split)
}

internal fun imageRegion(imageSize: IntSize, cropBounds: IntRect?, split: ReaderPageSplit): IntRect {
    val cropped = cropBounds ?: IntRect(IntOffset.Zero, imageSize)
    if (split == ReaderPageSplit.NONE) return cropped
    val halfWidth = cropped.width / 2
    val left = if (split == ReaderPageSplit.LEFT) cropped.left else cropped.right - halfWidth
    return IntRect(
        offset = IntOffset(left, cropped.top),
        size = IntSize(halfWidth, cropped.height),
    )
}
