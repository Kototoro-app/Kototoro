package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize

/**
 * Source abstraction for region-decodable images.
 *
 * Conforms to ADR 0002 Constraint 2: the pipeline owns decoding strategy; a
 * [RegionDecodeSource] exposes only the raw byte/decoder handle plus pre-decode
 * metadata, never presentation state.
 *
 * Implementations must be cheap to retain and safe to query from any thread;
 * the potentially blocking work happens inside [openSession].
 */
interface RegionDecodeSource {

    /** Fundamental metadata discovered prior to any pixel decoding. */
    val metadata: ImageSourceMetadata

    /** MIME type of the encoded payload, when known. */
    val mimeType: String?
        get() = metadata.mimeType

    /**
     * Opens a reusable decoding session for this source.
     *
     * A session owns the underlying platform decoder (e.g. [android.graphics.BitmapRegionDecoder])
     * and serializes/reuses it across many [TileDecodeSession.decodeRegion] calls so that
     * ultra-long strips are not re-parsed per tile. Callers must [TileDecodeSession.close]
     * sessions when the page leaves the working set.
     */
    suspend fun openSession(): TileDecodeSession
}

/**
 * Reusable region decoding session bound to one encoded image.
 *
 * All coordinates passed to [decodeRegion] are in **raw encoded pixel space**:
 * EXIF orientation, crop bounds, and page splitting are applied by [TileGrid]
 * before reaching a session. Payloads returned by [decodeRegion] are therefore
 * also in raw encoded orientation; the rendering layer applies the inverse
 * orientation transform described by [ImageSourceGeometry.orientationDegrees].
 */
interface TileDecodeSession : AutoCloseable {

    /** Raw encoded dimensions this session decodes from. */
    val encodedSize: IntSize

    /**
     * Decodes [region] (raw encoded coordinates, clamped to [encodedSize]) at a
     * power-of-two [sampleSize] (1 = full resolution).
     *
     * Implementations must recycle underlying native resources on [close]; the
     * returned payload becomes owned by the caller (typically the tile store).
     */
    suspend fun decodeRegion(region: IntRect, sampleSize: Int): Any
}
