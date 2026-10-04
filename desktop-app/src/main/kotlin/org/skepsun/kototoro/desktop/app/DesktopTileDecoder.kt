package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.reader.core.TileSpec
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.stream.FileImageInputStream

/** Region/subsample decoding through the JDK providers; never allocate a full-page destination. */
internal object DesktopTileDecoder {
    fun supported(path: Path): Boolean = FileImageInputStream(path.toFile()).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) false else readers.next().let { reader ->
            try { reader.formatName.lowercase() in setOf("png", "jpeg", "jpg") } finally { reader.dispose() }
        }
    }

    suspend fun decode(path: Path, spec: TileSpec): BufferedImage = DesktopImageDecoder.permits.withPermit {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(spec.estimatedBytes <= 2L * 1024 * 1024) { "Tile exceeds decode budget" }
        FileImageInputStream(path.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: error("图片格式暂不支持区域解码")
            var result: BufferedImage? = null
            try {
                reader.input = input
                reader.addIIOReadProgressListener(object : IIOReadProgressListener {
                    override fun imageProgress(source: ImageReader, percentageDone: Float) {
                        if (!context.isActive) source.abort()
                    }
                    override fun sequenceStarted(source: ImageReader, minIndex: Int) {}
                    override fun sequenceComplete(source: ImageReader) {}
                    override fun imageStarted(source: ImageReader, imageIndex: Int) {}
                    override fun imageComplete(source: ImageReader) {}
                    override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) {}
                    override fun thumbnailProgress(source: ImageReader, percentageDone: Float) {}
                    override fun thumbnailComplete(source: ImageReader) {}
                    override fun readAborted(source: ImageReader) {}
                })
                val region = spec.decodeRegion
                require(region.left >= 0 && region.top >= 0 && !region.isEmpty &&
                    region.right <= reader.getWidth(0) && region.bottom <= reader.getHeight(0))
                val parameter = reader.defaultReadParam.apply {
                    sourceRegion = Rectangle(region.left, region.top, region.width, region.height)
                    setSourceSubsampling(spec.sampleSize, spec.sampleSize, 0, 0)
                }
                result = reader.read(0, parameter)
                context.ensureActive()
                require(result.width == spec.decodedSize.width && result.height == spec.decodedSize.height)
                result.also { result = null }
            } finally { result?.flush(); reader.dispose() }
        }
    }
}
