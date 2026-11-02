package org.skepsun.kototoro.video.ui.compose

import kotlin.math.max
import kotlin.math.min

/**
 * Shape of the box drawn behind a subtitle cue (issue #595).
 *
 * [BAR] is the legacy full-width bar; [BLOCK] and [LINES] hug the text instead. [LINES] follows each
 * rendered line, so the background covers the words rather than the whole subtitle row.
 */
internal enum class SubtitleBackgroundStyle(val prefValue: Int) {
    NONE(0),
    BAR(1),
    BLOCK(2),
    LINES(3),
    ;

    companion object {
        val Default: SubtitleBackgroundStyle = LINES

        fun fromPrefValue(value: Int): SubtitleBackgroundStyle =
            entries.firstOrNull { it.prefValue == value } ?: Default
    }
}

/** Font family of the cue text; the app ships no custom fonts, so only the platform families are offered. */
internal enum class SubtitleFontFamily(val prefValue: Int) {
    SANS(0),
    SERIF(1),
    MONOSPACE(2),
    ;

    companion object {
        val Default: SubtitleFontFamily = SANS

        fun fromPrefValue(value: Int): SubtitleFontFamily =
            entries.firstOrNull { it.prefValue == value } ?: Default
    }
}

/** Axis-aligned rectangle used both for rendered line bounds and for the boxes drawn behind them. */
internal data class SubtitleRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Background boxes for one cue.
 *
 * [lines] are the rendered line bounds of the text layout, translated into the coordinate space of
 * the composable that draws the background. [paddingHorizontal]/[paddingVertical] grow the boxes
 * around the glyph area, and [containerWidth]/[containerHeight] clip them so a box never leaves the
 * drawing area of that composable.
 */
internal fun subtitleBackgroundBoxes(
    style: SubtitleBackgroundStyle,
    lines: List<SubtitleRect>,
    containerWidth: Float,
    containerHeight: Float,
    paddingHorizontal: Float,
    paddingVertical: Float,
): List<SubtitleRect> {
    if (style == SubtitleBackgroundStyle.NONE || lines.isEmpty()) return emptyList()
    if (containerWidth <= 0f || containerHeight <= 0f) return emptyList()
    val top = (lines.first().top - paddingVertical).coerceIn(0f, containerHeight)
    val bottom = (lines.last().bottom + paddingVertical).coerceIn(top, containerHeight)
    return when (style) {
        SubtitleBackgroundStyle.BAR -> listOf(
            SubtitleRect(left = 0f, top = top, right = containerWidth, bottom = bottom),
        )

        SubtitleBackgroundStyle.BLOCK -> {
            val left = (lines.minOf { it.left } - paddingHorizontal).coerceIn(0f, containerWidth)
            val right = (lines.maxOf { it.right } + paddingHorizontal).coerceIn(left, containerWidth)
            listOf(SubtitleRect(left = left, top = top, right = right, bottom = bottom))
        }

        SubtitleBackgroundStyle.LINES -> lines.mapIndexed { index, line ->
            val left = (line.left - paddingHorizontal).coerceIn(0f, containerWidth)
            val right = (line.right + paddingHorizontal).coerceIn(left, containerWidth)
            // Neighbouring pills share the gap up to its midpoint: alpha-blended overlaps would
            // otherwise read as a darker seam between the lines.
            val gapAbove = lines.getOrNull(index - 1)?.let { (it.bottom + line.top) / 2f } ?: 0f
            val gapBelow = lines.getOrNull(index + 1)?.let { (line.bottom + it.top) / 2f } ?: containerHeight
            val lineTop = (line.top - paddingVertical).coerceIn(min(gapAbove, line.bottom), line.bottom)
            val lineBottom = (line.bottom + paddingVertical).coerceIn(line.top, max(gapBelow, line.top))
            SubtitleRect(left = left, top = lineTop, right = right, bottom = lineBottom)
        }

        SubtitleBackgroundStyle.NONE -> emptyList()
    }
}
