package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelReaderPalette

class NovelReaderPanelColorsTest {

    // Values copied from novelReaderPalette(PAPER, isDarkTheme = false).
    private val paperLight = NovelReaderPalette(
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
    fun `panel takes the reading theme colours`() {
        val colors = novelReaderPanelColors(paperLight)
        assertEquals(Color(0xFFF4ECD8), colors.container)
        assertEquals(Color(0xFF4F4032), colors.content)
        assertEquals(Color(0xFF7A6A59), colors.contentSecondary)
        assertFalse(colors.isDark)
    }

    @Test
    fun `panel cards stay warm on a sepia theme instead of turning grey`() {
        val card = novelReaderPanelColors(paperLight).card
        assertNotEquals(Color(0xFFF4ECD8), card)
        assertTrue(card.red > card.blue)
    }
}
