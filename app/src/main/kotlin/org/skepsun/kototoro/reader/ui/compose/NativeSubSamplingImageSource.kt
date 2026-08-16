package org.skepsun.kototoro.reader.ui.compose

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Uses Mihon's native decoder for formats unsupported by Android's BitmapRegionDecoder. */
internal class NativeSubSamplingImageSource(
	context: Context,
	private val uri: Uri,
	override val preview: ImageBitmap? = null,
) : SubSamplingImageSource {

	private val context = context.applicationContext

	override suspend fun decoder(): ImageRegionDecoder.Factory = ImageRegionDecoder.Factory {
		withContext(Dispatchers.IO) {
			val decoder = try {
				withUriInputStream(context, uri) { input -> ImageDecoder.newInstance(input) }
			} catch (error: Exception) {
				throw IOException("Mihon image decoder cannot open: $uri", error)
			} ?: throw IOException("Mihon image decoder cannot open: $uri")
			NativeImageRegionDecoder(decoder, uri)
		}
	}

	override fun toString(): String = "NativeSubSamplingImageSource(uri=$uri)"
}

private class NativeImageRegionDecoder(
	private val decoder: ImageDecoder,
	private val uri: Uri,
) : ImageRegionDecoder {

	override val imageSize = IntSize(decoder.width, decoder.height)

	override suspend fun decodeRegion(
		region: IntRect,
		sampleSize: Int,
	): ImageRegionDecoder.DecodeResult = withContext(Dispatchers.IO) {
		val bitmap = try {
			decoder.decode(region.toAndroidRect(), sampleSize.coerceAtLeast(1))
		} catch (error: Exception) {
			throw IOException("Mihon image decoder failed for $uri region $region", error)
		}
			?: throw IOException("Mihon image decoder returned null for $uri region $region")
		bitmap.prepareToDraw()
		ImageRegionDecoder.DecodeResult(
			painter = BitmapPainter(bitmap.asImageBitmap()),
			hasUltraHdrContent = false,
		)
	}

	override fun close() = decoder.recycle()
}

private inline fun <T> withUriInputStream(
	context: Context,
	uri: Uri,
	block: (InputStream) -> T,
): T? {
	return if (uri.isContentZipUri()) {
		val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
		context.contentResolver.openInputStream(uri.toUnderlyingZipUri())?.use { input ->
			ZipInputStream(input.buffered()).use { zip ->
				var entry = zip.nextEntry
				while (entry != null && entry.name != entryName) {
					entry = zip.nextEntry
				}
				if (entry == null) throw IOException("ZIP entry not found: $entryName")
				block(zip)
			}
		}
	} else if (uri.isZipUri()) {
		ZipFile(uri.schemeSpecificPart).use { zip ->
			val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
			val entry = zip.getEntry(entryName) ?: throw IOException("ZIP entry not found: $entryName")
			if (entry.isDirectory) throw IOException("ZIP entry is a directory: $entryName")
			zip.getInputStream(entry).use(block)
		}
	} else {
		context.contentResolver.openInputStream(uri)?.use(block)
	}
}

private fun IntRect.toAndroidRect() = Rect(left, top, right, bottom)

internal fun Uri.isAvifImage(): Boolean {
	val fileName = fragment ?: lastPathSegment ?: return false
	return fileName.substringAfterLast('.', missingDelimiterValue = "").equals("avif", ignoreCase = true)
}
