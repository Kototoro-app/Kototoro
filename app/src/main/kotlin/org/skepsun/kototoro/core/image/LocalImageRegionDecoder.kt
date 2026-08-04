package org.skepsun.kototoro.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.skepsun.kototoro.core.util.ext.isZipUri
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

class LocalImageRegionDecoder private constructor(
	private val uri: Uri,
	private val decoder: BitmapRegionDecoder,
	private val bitmapConfig: Bitmap.Config,
	private val exif: LocalExifMetadata,
) : Closeable {

	val size = Point(decoder.width, decoder.height)

	fun decodeRegion(region: Rect, sampleSize: Int): Bitmap {
		val options = BitmapFactory.Options().apply {
			inPreferredConfig = bitmapConfig
			inSampleSize = sampleSize.coerceAtLeast(1)
		}
		return decoder.decodeRegion(region, options) ?: throw ImageDecodeException(
			uri = uri.toString(),
			format = null,
		)
	}

	fun toDisplayRect(region: Rect): Rect {
		fun mapPoint(x: Int, y: Int): Point {
			val transformedX = if (exif.flippedHorizontally) size.x - x else x
			return when (exif.rotationDegrees) {
				90 -> Point(size.y - y, transformedX)
				180 -> Point(size.x - transformedX, size.y - y)
				270 -> Point(y, size.x - transformedX)
				else -> Point(transformedX, y)
			}
		}
		val corners = arrayOf(
			mapPoint(region.left, region.top),
			mapPoint(region.right, region.top),
			mapPoint(region.left, region.bottom),
			mapPoint(region.right, region.bottom),
		)
		return Rect(
			corners.minOf(Point::x),
			corners.minOf(Point::y),
			corners.maxOf(Point::x),
			corners.maxOf(Point::y),
		)
	}

	override fun close() {
		decoder.recycle()
	}

	companion object {
		fun open(
			contentResolver: ContentResolver,
			uri: Uri,
			bitmapConfig: Bitmap.Config,
		): LocalImageRegionDecoder {
			val exif = runCatching {
				withUriInputStream(contentResolver, uri) { input ->
					val metadata = ExifInterface(LocalExifInputStream(input))
					LocalExifMetadata(metadata.rotationDegrees, metadata.isFlipped)
				}
			}.getOrNull() ?: LocalExifMetadata()
			val decoder = try {
				withUriInputStream(contentResolver, uri, BitmapDecoderCompat::createRegionDecoder)
			} catch (error: Throwable) {
				throw ImageDecodeException(uri.toString(), null, cause = error)
			} ?: throw ImageDecodeException(uri.toString(), null)
			return LocalImageRegionDecoder(uri, decoder, bitmapConfig, exif)
		}
	}
}

private data class LocalExifMetadata(
	val rotationDegrees: Int = 0,
	val flippedHorizontally: Boolean = false,
)

private inline fun <T> withUriInputStream(
	contentResolver: ContentResolver,
	uri: Uri,
	block: (InputStream) -> T,
): T? {
	return if (uri.isZipUri()) {
		ZipFile(uri.schemeSpecificPart).use { zip ->
			val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
			val entry = zip.getEntry(entryName) ?: throw IOException("ZIP entry not found: $entryName")
			zip.getInputStream(entry).use(block)
		}
	} else {
		contentResolver.openInputStream(uri)?.use(block)
	}
}

private class LocalExifInputStream(private val delegate: InputStream) : InputStream() {
	private var availableBytes = 1024 * 1024 * 1024

	override fun available(): Int = availableBytes
	override fun read(): Int = intercept(delegate.read())
	override fun read(buffer: ByteArray): Int = intercept(delegate.read(buffer))
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int = intercept(delegate.read(buffer, offset, length))
	override fun skip(byteCount: Long): Long = delegate.skip(byteCount)
	override fun close() = delegate.close()

	private fun intercept(bytesRead: Int): Int {
		if (bytesRead == -1) availableBytes = 0
		return bytesRead
	}
}
