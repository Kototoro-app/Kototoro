package org.skepsun.kototoro.video.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

enum class Media3PlaybackErrorCategory {
    NETWORK,
    HTTP,
    MANIFEST,
    MEDIA_CHUNK,
    DECODER,
    SURFACE,
    UNKNOWN,
}

data class ClassifiedPlaybackError(
    val category: Media3PlaybackErrorCategory,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
)

object Media3PlaybackErrorClassifier {
    fun classify(error: PlaybackException): ClassifiedPlaybackError {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null) {
            return ClassifiedPlaybackError(
                category = Media3PlaybackErrorCategory.HTTP,
                httpStatus = http.responseCode,
                retryable = http.responseCode >= 500,
            )
        }
        val category = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> Media3PlaybackErrorCategory.NETWORK
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> Media3PlaybackErrorCategory.MANIFEST
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> Media3PlaybackErrorCategory.HTTP
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> Media3PlaybackErrorCategory.DECODER
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            -> Media3PlaybackErrorCategory.SURFACE
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            -> Media3PlaybackErrorCategory.MEDIA_CHUNK
            else -> Media3PlaybackErrorCategory.UNKNOWN
        }
        return ClassifiedPlaybackError(
            category = category,
            retryable = category == Media3PlaybackErrorCategory.NETWORK,
        )
    }
}
