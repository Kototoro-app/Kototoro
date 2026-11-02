package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.desktop.runtime.NovelBlock

/**
 * The novel text styles, shared by the block views and the paged reader's measurement.
 *
 * Paged mode measures a block with exactly the [TextStyle] the block renders with, so a page break sits where the
 * text actually ends instead of following an approximation that drifts with the font.
 */
internal class DesktopNovelTextStyles(settings: DesktopNovelSettings) {
    private val palette = settings.theme.palette
    private val family = if (settings.serif) FontFamily.Serif else FontFamily.Default
    private val fontSize = settings.fontSize

    val body = TextStyle(
        color = Color(palette.text),
        fontSize = fontSize.sp,
        lineHeight = (fontSize * settings.lineSpacing).sp,
        fontFamily = family,
    )

    fun heading(level: Int) = TextStyle(
        color = Color(palette.text),
        fontSize = (fontSize + (7 - level).coerceIn(1, 6) * 2).sp,
        fontFamily = family,
        fontWeight = FontWeight.Bold,
    )
    val muted: Color = Color(palette.secondaryText)
}

internal fun DesktopNovelSettings.desktopNovelStyles() = DesktopNovelTextStyles(this)

/** The paragraph as the reader draws it: runs keep their bold and italic spans. */
internal fun NovelBlock.Paragraph.annotatedText(): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        withStyle(SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
        )) { append(run.text) }
    }
}
