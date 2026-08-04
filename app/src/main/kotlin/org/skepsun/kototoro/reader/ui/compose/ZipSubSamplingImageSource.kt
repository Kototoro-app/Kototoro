package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class ZipSubSamplingImageSource(
	private val zipFile: File,
	private val entryName: String,
	override val preview: ImageBitmap? = null,
) : SubSamplingImageSource {

	override suspend fun decoder(): ImageRegionDecoder.Factory = ImageRegionDecoder.Factory { params ->
		withContext(Dispatchers.IO) {
			ZipFile(zipFile).use { zip ->
				val entry = zip.getEntry(entryName)
					?: throw IOException("ZIP entry not found: $entryName")
				if (entry.isDirectory) {
					throw IOException("ZIP entry is a directory: $entryName")
				}
				val exif = zip.getInputStream(entry).use { input ->
					readExifMetadata(input)
				}
				val decoder = zip.getInputStream(entry).use { input ->
					createBitmapRegionDecoder(input)
						?: throw IOException("Cannot create region decoder for ZIP entry: $entryName")
				}
				ZipImageRegionDecoder(
					decoder = decoder,
					bitmapConfig = params.imageOptions.config.toAndroidBitmapConfig(),
					exif = exif,
				)
			}
		}
	}

	override fun toString(): String = "ZipSubSamplingImageSource(zipFile=$zipFile, entryName=$entryName)"
}

private class ZipImageRegionDecoder(
	private val decoder: BitmapRegionDecoder,
	private val bitmapConfig: Bitmap.Config,
	private val exif: ExifMetadata,
) : ImageRegionDecoder {

	private val decoderLock = ReentrantReadWriteLock(true)
	private val rawImageSize = IntSize(decoder.width, decoder.height)

	override val imageSize: IntSize = if (exif.rotationDegrees == 90 || exif.rotationDegrees == 270) {
		IntSize(rawImageSize.height, rawImageSize.width)
	} else {
		rawImageSize
	}

	override suspend fun decodeRegion(
		region: IntRect,
		sampleSize: Int,
	): ImageRegionDecoder.DecodeResult = withContext(Dispatchers.IO) {
		decoderLock.read {
			if (decoder.isRecycled) {
				throw IOException("Cannot decode a region after the ZIP image decoder was closed")
			}
			val rawRegion = region.toRawImageRegion()
			val options = BitmapFactory.Options().apply {
				inSampleSize = sampleSize.coerceAtLeast(1)
				inPreferredConfig = bitmapConfig
			}
			val bitmap = decoder.decodeRegion(rawRegion.toAndroidRect(), options)
				?: throw IOException("BitmapRegionDecoder returned null for region $rawRegion")
			bitmap.prepareToDraw()
			ImageRegionDecoder.DecodeResult(
				painter = ExifAwareBitmapPainter(bitmap, exif),
				hasUltraHdrContent = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap(),
			)
		}
	}

	override fun close() {
		decoderLock.write {
			if (!decoder.isRecycled) {
				decoder.recycle()
			}
		}
	}

	private fun IntRect.toRawImageRegion(): IntRect {
		val displayBounds = IntRect(IntOffset.Zero, imageSize)
		var transformed = rotateRegionBy(-exif.rotationDegrees, displayBounds)
		if (exif.flippedHorizontally) {
			val boundsAfterRotation = if (exif.rotationDegrees == 90 || exif.rotationDegrees == 270) {
				IntRect(IntOffset.Zero, IntSize(displayBounds.height, displayBounds.width))
			} else {
				displayBounds
			}
			transformed = transformed.flipRegionHorizontally(boundsAfterRotation)
		}
		return transformed
	}
}

private data class ExifMetadata(
	val rotationDegrees: Int,
	val flippedHorizontally: Boolean,
)

private fun readExifMetadata(input: InputStream): ExifMetadata = try {
	val exif = ExifInterface(ExifCompatibleInputStream(input))
	ExifMetadata(
		rotationDegrees = exif.rotationDegrees,
		flippedHorizontally = exif.isFlipped,
	)
} catch (_: IOException) {
	ExifMetadata(rotationDegrees = 0, flippedHorizontally = false)
}

private class ExifCompatibleInputStream(private val delegate: InputStream) : InputStream() {
	// ExifInterface otherwise treats some ZIP entry streams as prematurely exhausted.
	private var availableBytes = 1024 * 1024 * 1024

	override fun available(): Int = availableBytes

	override fun read(): Int = intercept(delegate.read())

	override fun read(buffer: ByteArray): Int = intercept(delegate.read(buffer))

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		intercept(delegate.read(buffer, offset, length))

	override fun skip(byteCount: Long): Long = delegate.skip(byteCount)

	override fun close() = delegate.close()

	private fun intercept(bytesRead: Int): Int {
		if (bytesRead == -1) {
			availableBytes = 0
		}
		return bytesRead
	}
}

private class ExifAwareBitmapPainter(
	private val bitmap: Bitmap,
	private val exif: ExifMetadata,
) : Painter() {

	override val intrinsicSize: Size = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
	private val paint = androidx.compose.ui.graphics.Paint()
	private val matrix = Matrix()

	override fun applyAlpha(alpha: Float): Boolean {
		paint.alpha = alpha
		return true
	}

	override fun applyColorFilter(colorFilter: androidx.compose.ui.graphics.ColorFilter?): Boolean {
		paint.colorFilter = colorFilter
		return true
	}

	override fun DrawScope.onDraw() {
		matrix.reset()
		val bitmapCenter = Offset(intrinsicSize.width / 2f, intrinsicSize.height / 2f)
		matrix.postTranslate(-bitmapCenter.x, -bitmapCenter.y)
		if (exif.flippedHorizontally) {
			matrix.postScale(-1f, 1f)
		}
		matrix.postRotate(exif.rotationDegrees.toFloat())
		val rotatedSize = if (exif.rotationDegrees % 180 == 0) intrinsicSize else intrinsicSize.swap()
		matrix.postScale(size.width / rotatedSize.width, size.height / rotatedSize.height)
		matrix.postTranslate(size.width / 2f, size.height / 2f)
		drawIntoCanvas { canvas ->
			canvas.nativeCanvas.drawBitmap(bitmap, matrix, paint.nativePaint)
		}
	}
}

private fun createBitmapRegionDecoder(input: InputStream): BitmapRegionDecoder? =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		BitmapRegionDecoder.newInstance(input)
	} else {
		@Suppress("DEPRECATION")
		BitmapRegionDecoder.newInstance(input, false)
	}

private fun ImageBitmapConfig.toAndroidBitmapConfig(): Bitmap.Config = when {
	Build.VERSION.SDK_INT >= 26 && this == ImageBitmapConfig.F16 -> Bitmap.Config.RGBA_F16
	this == ImageBitmapConfig.Rgb565 -> Bitmap.Config.RGB_565
	this == ImageBitmapConfig.Alpha8 -> Bitmap.Config.ALPHA_8
	else -> Bitmap.Config.ARGB_8888
}

private fun IntRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)

private fun IntRect.rotateRegionBy(degrees: Int, parent: IntRect): IntRect {
	if (degrees == 0) return this
	val newTopLeft = when (degrees) {
		-270, 90 -> {
			val offset = parent.bottomLeft - bottomLeft
			IntOffset(offset.y, -offset.x)
		}
		-180, 180 -> parent.bottomRight - bottomRight
		-90, 270 -> {
			val offset = parent.topRight - topRight
			IntOffset(-offset.y, offset.x)
		}
		else -> error("Unsupported EXIF rotation: $degrees")
	}
	val newSize = if (degrees == -180 || degrees == 180) size else IntSize(height, width)
	return IntRect(newTopLeft, newSize)
}

private fun IntRect.flipRegionHorizontally(parent: IntRect): IntRect = IntRect(
	topLeft = IntOffset(parent.width - bottomRight.x, topLeft.y),
	bottomRight = IntOffset(parent.width - topLeft.x, bottomRight.y),
)

private fun Size.swap(): Size = Size(width = height, height = width)
