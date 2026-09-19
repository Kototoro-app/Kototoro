package org.skepsun.kototoro.reader.novel.annotation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.R

enum class NovelMarkingColor(
    val id: Int,
    @StringRes val displayNameRes: Int,
    val lineHex: Long,
    val bgHex: Long,
) {
    YELLOW(0, R.string.novel_marking_color_yellow, 0xFFF59E0B, 0x59FDE68A),
    GREEN(1, R.string.novel_marking_color_green, 0xFF10B981, 0x59A7F3D0),
    BLUE(2, R.string.novel_marking_color_blue, 0xFF3B82F6, 0x59BFDBFE),
    PINK(3, R.string.novel_marking_color_pink, 0xFFEC4899, 0x59FBCFE8),
    ORANGE(4, R.string.novel_marking_color_orange, 0xFFF97316, 0x59FED7AA);

    val lineColor: Color get() = Color(lineHex)
    val bgColor: Color get() = Color(bgHex)

    companion object {
        fun fromId(id: Int): NovelMarkingColor = entries.find { it.id == id } ?: YELLOW
    }
}

enum class NovelMarkingStyle(
    val id: Int,
    @StringRes val displayNameRes: Int,
) {
    UNDERLINE(0, R.string.novel_marking_style_underline),
    WAVY(1, R.string.novel_marking_style_wavy),
    HIGHLIGHT(2, R.string.novel_marking_style_highlight);

    companion object {
        fun fromId(id: Int): NovelMarkingStyle = entries.find { it.id == id } ?: UNDERLINE
    }
}
