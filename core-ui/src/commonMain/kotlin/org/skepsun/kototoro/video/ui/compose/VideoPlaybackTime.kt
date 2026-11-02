package org.skepsun.kototoro.video.ui.compose

/**
 * A player clock, drawn the way Android draws it: `M:SS`, or `H:MM:SS` once the value reaches an hour.
 *
 * [padMinutes] keeps the digits from jittering where the clock sits next to a slider or inside a seeking bubble —
 * Android pads the minutes there, so `0:05` becomes `00:05`. [forceHours] takes the shape from the episode length
 * instead of the position: the seek bubble of an hour-long episode shows `00:12:03 / 01:40:00` even at the start.
 */
fun formatPlaybackTime(millis: Long, padMinutes: Boolean = false, forceHours: Boolean = false): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L || forceHours ->
            if (padMinutes) "${two(hours)}:${two(minutes)}:${two(seconds)}" else "$hours:${two(minutes)}:${two(seconds)}"
        padMinutes -> "${two(minutes)}:${two(seconds)}"
        else -> "$minutes:${two(seconds)}"
    }
}

private fun two(value: Long): String = if (value < 10L) "0$value" else value.toString()
