package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelReaderPalette

class NovelChromeColorsTest {

    private val paper = NovelReaderPalette(
        backgroundColor = 0xFFF4ECD8.toInt(),
        textColor = 0xFF4F4032.toInt(),
        secondaryTextColor = 0xFF7A6A59.toInt(),
        chromeBackgroundColor = 0xFFE7DDC5.toInt(),
        chromeTextColor = 0xFF544436.toInt(),
        highlightColor = 0x4DA67C2E,
        placeholderColor = 0xFFDDD2BC.toInt(),
        placeholderTextColor = 0xFF7A6A59.toInt(),
        isDark = false,
    )

    @Test
    fun `md3 novel chrome surfaces take the theme chrome colour`() {
        val scheme = novelChromeColorScheme(lightColorScheme(), paper)
        listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
            .forEach { assertEquals(Color(0xFFE7DDC5), it) }
        assertEquals(Color(0xFF544436), scheme.onSurface)
        assertEquals(Color(0xFF544436), scheme.primary)
    }
}
