package org.skepsun.kototoro.video.domain

import kotlin.math.roundToLong

/**
 * Size labels for video streams.
 *
 * Sizes are always best-effort: many sources (HLS/DASH manifests, torrents, links behind a
 * redirect chain) never expose a total byte count, so callers must treat [parseVideoSizeHint]
 * and the probe in `VideoStreamSizeProbe` as optional hints and simply omit the label when the
 * result is `null`.
 */

private const val BYTES_PER_KIB = 1024.0
private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024.0
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024.0

private val SIZE_HINT_REGEX = Regex(
    """(?:^|[^\d.])(\d{1,5}(?:[.,]\d{1,2})?)\s*(kib|mib|gib|tib|kb|mb|gb|tb|b)(?![a-z0-9])""",
    RegexOption.IGNORE_CASE,
)

/**
 * Extracts a byte count that many extensions already bake into the stream title, e.g.
 * `1080p · 700 MB` or `Server A 1.4GB`. Returns `null` when the title carries no size token.
 */
fun parseVideoSizeHint(title: String): Long? {
    if (title.isBlank()) return null
    val match = SIZE_HINT_REGEX.find(title) ?: return null
    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    if (amount <= 0.0) return null
    val unitBytes = when (match.groupValues[2].lowercase()) {
        "kib", "kb" -> BYTES_PER_KIB
        "mib", "mb" -> BYTES_PER_MIB
        "gib", "gb" -> BYTES_PER_GIB
        "tib", "tb" -> BYTES_PER_GIB * 1024.0
        else -> 1.0
    }
    val bytes = (amount * unitBytes).roundToLong()
    return bytes.takeIf { it > 0 }
}

/**
 * Compact human readable size, e.g. `845 B`, `1.5 KB`, `700 MB`, `1.35 GB`.
 * Trailing zeros are trimmed so a known-but-round size never renders as `1.0 GB`.
 */
fun formatVideoSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < BYTES_PER_KIB) return "$bytes B"
    return when {
        bytes < BYTES_PER_MIB -> "${decimal(bytes / BYTES_PER_KIB, 1)} KB"
        bytes < BYTES_PER_GIB -> "${decimal(bytes / BYTES_PER_MIB, 1)} MB"
        else -> "${decimal(bytes / BYTES_PER_GIB, 2)} GB"
    }
}

private fun decimal(value: Double, digits: Int): String {
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = (value * factor).roundToLong()
    val whole = scaled / factor
    val fraction = scaled % factor
    if (fraction == 0L) return whole.toString()
    val fractionText = fraction.toString().padStart(digits, '0').trimEnd('0')
    return "$whole.$fractionText"
}
