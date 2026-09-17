package org.skepsun.kototoro.reader.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Android binding producing [RegionDecodeSource]s backed by
 * [BitmapRegionDecoder] (API 26..37 compatible construction).
 *
 * URI handling mirrors the reader's other region decoders:
 * - `content+zip://…#entry` → stream scan inside a content zip,
 * - `zip://`/`cbz://`/legacy `zip://` file schemes → [ZipFile] direct entry access,
 * - everything else (file://, content://, https://) → [Context.contentResolver].
 *
 * EXIF orientation is resolved once at creation time and folded into
 * [ImageSourceGeometry]; the raw decoder never applies it, so [TileGrid]
 * performs the logical → encoded mapping. This factory is intentionally thin:
 * all scheduling/eviction policy lives in [ReaderTileManager] and pure-Kotlin types.
 */
class AndroidRegionDecoderFactory(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Probes [uri] (bounds + MIME + EXIF rotation) and builds a lazily-sessioned source.
     *
     * @param isAnimatedHint Optional authoritative animated flag (e.g. from Coil);
     *   when null, only `image/gif` is treated as animated from the MIME type.
     * @param geometryOverride Optional geometry override (e.g. persisted crop bounds).
     */
    suspend fun create(
        uri: Uri,
        isAnimatedHint: Boolean? = null,
        geometryOverride: ImageSourceGeometry? = null,
    ): RegionDecodeSource = withContext(Dispatchers.IO) {
        val (width, height, mimeType) = probeBounds(uri)
        if (width <= 0 || height <= 0) {
            throw IOException("Cannot decode image bounds for $uri")
        }
        val metadata = ImageSourceMetadata(
            size = IntSize(width, height),
            mimeType = mimeType,
            isAnimated = isAnimatedHint ?: (mimeType == MIME_GIF),
        )
        val geometry = geometryOverride ?: ImageSourceGeometry(
            encodedSize = IntSize(width, height),
            orientationDegrees = readExifRotation(uri),
        )
        AndroidRegionDecodeSource(
            uri = uri,
            metadata = metadata,
            geometry = geometry,
            factory = this@AndroidRegionDecoderFactory,
        )
    }

    private fun openStream(uri: Uri, block: (InputStream) -> Unit) {
        if (uri.isContentZipUri()) {
            val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
            appContext.contentResolver.openInputStream(uri.toUnderlyingZipUri())?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && entry.name != entryName) {
                        entry = zip.nextEntry
                    }
                    if (entry == null) throw IOException("ZIP entry not found: $entryName")
                    block(zip)
                }
            } ?: throw IOException("Cannot open content zip: $uri")
        } else if (uri.isZipUri()) {
            ZipFile(uri.schemeSpecificPart).use { zip ->
                val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
                val entry = zip.getEntry(entryName) ?: throw IOException("ZIP entry not found: $entryName")
                if (entry.isDirectory) throw IOException("ZIP entry is a directory: $entryName")
                zip.getInputStream(entry).use(block)
            }
        } else {
            appContext.contentResolver.openInputStream(uri)?.use(block)
                ?: throw IOException("Cannot open stream: $uri")
        }
    }

    private fun probeBounds(uri: Uri): Triple<Int, Int, String?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri) { input -> BitmapFactory.decodeStream(input, null, options) }
        return Triple(options.outWidth, options.outHeight, options.outMimeType)
    }

    private fun readExifRotation(uri: Uri): Int {
        var rotation = 0
        try {
            openStream(uri) { input ->
                rotation = when (val degrees = ExifInterface(input).rotationDegrees) {
                    90, 180, 270 -> degrees
                    else -> 0
                }
            }
        } catch (_: IOException) {
            rotation = 0
        }
        return rotation
    }

    private fun openSessionInternal(uri: Uri): TileDecodeSession {
        var decoder: BitmapRegionDecoder? = null
        openStream(uri) { input ->
            decoder = createBitmapRegionDecoder(input)
        }
        val resolved = decoder ?: throw IOException("Cannot create region decoder for $uri")
        return AndroidTileDecodeSession(resolved)
    }

    private class AndroidRegionDecodeSource(
        private val uri: Uri,
        override val metadata: ImageSourceMetadata,
        val geometry: ImageSourceGeometry,
        private val factory: AndroidRegionDecoderFactory,
    ) : RegionDecodeSource {
        override val mimeType: String? get() = metadata.mimeType

        override suspend fun openSession(): TileDecodeSession = withContext(Dispatchers.IO) {
            factory.openSessionInternal(uri)
        }

        override fun toString(): String = "AndroidRegionDecodeSource(uri=$uri)"
    }

    private class AndroidTileDecodeSession(
        private val decoder: BitmapRegionDecoder,
    ) : TileDecodeSession {
        private val lock = ReentrantReadWriteLock(true)

        override val encodedSize: IntSize = IntSize(decoder.width, decoder.height)

        override suspend fun decodeRegion(region: IntRect, sampleSize: Int): Any =
            withContext(Dispatchers.IO) {
                lock.read {
                    if (decoder.isRecycled) {
                        throw IOException("Region decoder was closed for $encodedSize")
                    }
                    val clamped = region.intersectionOrNull(IntRect.fromLtwh(0, 0, encodedSize.width, encodedSize.height))
                        ?: throw IOException("Decode region $region is outside encoded bounds $encodedSize")
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = decoder.decodeRegion(
                        Rect(clamped.left, clamped.top, clamped.right, clamped.bottom),
                        options,
                    ) ?: throw IOException("Region decoder returned null for $clamped")
                    bitmap.prepareToDraw()
                    bitmap
                }
            }

        override fun close() {
            lock.write {
                if (!decoder.isRecycled) {
                    decoder.recycle()
                }
            }
        }
    }

    private companion object {
        const val MIME_GIF = "image/gif"

        /** API 26..37 compatible construction: non-deprecated overload from S onward. */
        fun createBitmapRegionDecoder(input: InputStream): BitmapRegionDecoder? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(input)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(input, false)
            }
    }
}
