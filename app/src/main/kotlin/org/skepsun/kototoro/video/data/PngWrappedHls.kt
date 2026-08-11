package org.skepsun.kototoro.video.data

import java.io.BufferedInputStream
import java.io.InputStream

private val PNG_IEND = byteArrayOf(
    0x00,
    0x00,
    0x00,
    0x00,
    0x49,
    0x45,
    0x4E,
    0x44,
    0xAE.toByte(),
    0x42,
    0x60,
    0x82.toByte(),
)
private const val PNG_WRAPPER_SCAN_LIMIT = 64 * 1024

internal data class PngUnwrapResult(
    val stream: InputStream,
    val wasUnwrapped: Boolean,
    val isTransportStream: Boolean,
)

internal fun unwrapPngPrefixedStream(input: InputStream): PngUnwrapResult {
    val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
    if (buffered.startsWithTransportStreamSyncByte()) {
        return PngUnwrapResult(
            stream = buffered,
            wasUnwrapped = false,
            isTransportStream = true,
        )
    }
    buffered.mark(PNG_WRAPPER_SCAN_LIMIT)
    var matched = 0
    repeat(PNG_WRAPPER_SCAN_LIMIT) {
        val value = buffered.read()
        if (value < 0) {
            buffered.reset()
            return PngUnwrapResult(
                stream = buffered,
                wasUnwrapped = false,
                isTransportStream = buffered.startsWithTransportStreamSyncByte(),
            )
        }
        matched = when {
            value.toByte() == PNG_IEND[matched] -> matched + 1
            value.toByte() == PNG_IEND[0] -> 1
            else -> 0
        }
        if (matched == PNG_IEND.size) {
            return PngUnwrapResult(
                stream = buffered,
                wasUnwrapped = true,
                isTransportStream = buffered.startsWithTransportStreamSyncByte(),
            )
        }
    }
    buffered.reset()
    return PngUnwrapResult(
        stream = buffered,
        wasUnwrapped = false,
        isTransportStream = buffered.startsWithTransportStreamSyncByte(),
    )
}

private fun BufferedInputStream.startsWithTransportStreamSyncByte(): Boolean {
    mark(1)
    val firstByte = read()
    reset()
    return firstByte == 0x47
}
