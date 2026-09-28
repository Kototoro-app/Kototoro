package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.reader.novel.NovelReaderPalette
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.readerPanelColors

internal fun novelReaderPanelColors(palette: NovelReaderPalette): ReaderPanelColors = readerPanelColors(
    base = Color(palette.backgroundColor),
    content = Color(palette.textColor),
    contentSecondary = Color(palette.secondaryTextColor),
    accent = Color(palette.chromeTextColor),
    isDark = palette.isDark,
)
