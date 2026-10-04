package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import org.skepsun.kototoro.reader.core.ImageDecodeSize
import org.skepsun.kototoro.reader.core.IntSize
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream

/** Bounds destination pixels and concurrent work, not native codec scratch space or total UI residency. */
internal object DesktopImageDecoder {
    internal val permits = Semaphore(2)
    const val MAXIMUM_PIXELS = 4L * 1024 * 1024
    const val MAXIMUM_DIMENSION = 16384

    fun header(path: Path): DesktopReaderImage {
        FileImageInputStream(path.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    if (reader.formatName.lowercase() in setOf("png", "jpeg", "jpg")) {
                        reader.input = input
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        require(width > 0 && height > 0) { "页面尺寸无效" }
                        return DesktopReaderImage(path, width, height, true)
                    }
                } finally { reader.dispose() }
            }
        }
        return Image.makeFromEncoded(Files.readAllBytes(path)).use {
            require(it.width > 0 && it.height > 0) { "页面尺寸无效" }
            DesktopReaderImage(path, it.width, it.height)
        }
    }

    /** Caller owns the returned bitmap; Compose may adopt it without another full-size pixel copy. */
    suspend fun decode(path: Path): Bitmap = permits.withPermit {
        val context = currentCoroutineContext()
        context.ensureActive()
        Image.makeFromEncoded(Files.readAllBytes(path)).use { image ->
            context.ensureActive()
            val size = ImageDecodeSize.resolve(IntSize(image.width, image.height), MAXIMUM_PIXELS, MAXIMUM_DIMENSION)
            val bitmap = Bitmap()
            try {
                check(bitmap.allocPixels(image.imageInfo.withWidthHeight(size.width, size.height)
                    .withColorType(ColorType.N32).withColorAlphaType(ColorAlphaType.PREMUL))) { "页面像素分配失败" }
                requireNotNull(bitmap.peekPixels()).use { pixels ->
                    check(image.scalePixels(pixels, SamplingMode.LINEAR, false)) { "页面解码失败" }
                }
                context.ensureActive()
                bitmap.setImmutable()
            } catch (error: Throwable) { bitmap.close(); throw error }
        }
    }
}
