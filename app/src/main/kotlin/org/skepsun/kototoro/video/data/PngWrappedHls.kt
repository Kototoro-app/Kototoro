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
)

internal fun unwrapPngPrefixedStream(input: InputStream): PngUnwrapResult {
    val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
    buffered.mark(PNG_WRAPPER_SCAN_LIMIT)
    var matched = 0
    repeat(PNG_WRAPPER_SCAN_LIMIT) {
        val value = buffered.read()
        if (value < 0) {
            buffered.reset()
            return PngUnwrapResult(buffered, wasUnwrapped = false)
        }
        matched = when {
            value.toByte() == PNG_IEND[matched] -> matched + 1
            value.toByte() == PNG_IEND[0] -> 1
            else -> 0
        }
        if (matched == PNG_IEND.size) {
            return PngUnwrapResult(buffered, wasUnwrapped = true)
        }
    }
    buffered.reset()
    return PngUnwrapResult(buffered, wasUnwrapped = false)
}
