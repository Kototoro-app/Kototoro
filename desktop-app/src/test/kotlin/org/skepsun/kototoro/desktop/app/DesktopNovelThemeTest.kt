package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelReaderColors
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.novelReaderColors

/**
 * The Windows novel reader takes its four themes from `reader-novel`'s palettes, which are the ones Android draws with.
 * These tests pin the mapping, so Windows keeps showing the same surfaces instead of drifting into private colors, and
 * pin the stored names, because the enum name is what the settings store and the source preference values hold.
 */
internal class DesktopNovelThemeTest {

    /** Android's palettes as `reader-novel` defines them: background, text, secondary text, highlight, isDark. */
    private val android = mapOf(
        DesktopNovelTheme.LIGHT to NovelReaderColors(
            0xFFF4ECD8, 0xFF4F4032, 0xFF7A6A59, 0xFFE7DDC5, 0xFF544436, 0x4DA67C2E, 0xFFDDD2BC, 0xFF7A6A59, false,
        ),
        DesktopNovelTheme.SEPIA to NovelReaderColors(
            0xFFF1E4CC, 0xFF5A4330, 0xFF8A7057, 0xFFE4D3B7, 0xFF614734, 0x4DA87B2C, 0xFFDCC7A6, 0xFF8A7057, false,
        ),
        DesktopNovelTheme.GREEN to NovelReaderColors(
            0xFFE6E9DB, 0xFF36402E, 0xFF61705A, 0xFFD6DBC8, 0xFF3D4735, 0x4D6F8A42, 0xFFC8D0BA, 0xFF61705A, false,
        ),
        DesktopNovelTheme.DARK to NovelReaderColors(
            0xFF1D2329, 0xFFD7DDE2, 0xFFAEB8C0, 0xFF252D34, 0xFFE3E8EC, 0x4D5F8EAD, 0xFF313A42, 0xFFAEB8C0, true,
        ),
    )

    @Test
    fun `every reader theme is one of Android's palettes`() {
        DesktopNovelTheme.entries.forEach { theme ->
            assertEquals(android.getValue(theme), theme.palette) { "${theme.name} is not Android's palette" }
        }
    }

    @Test
    fun `only the dark theme uses a dark variant`() {
        assertTrue(DesktopNovelTheme.DARK.palette.isDark)
        DesktopNovelTheme.entries.filter { it != DesktopNovelTheme.DARK }.forEach {
            assertFalse(it.palette.isDark) { "${it.name} should use the light variant" }
        }
        // LIGHT is Android's default paper surface, not a plain white page.
        assertEquals(NovelReaderThemePreset.PAPER, DesktopNovelTheme.LIGHT.preset)
        assertEquals(0xFFF4ECD8, DesktopNovelTheme.LIGHT.palette.background)
    }

    @Test
    fun `the stored theme names are the ones preferences and source values carry`() {
        assertEquals(listOf("LIGHT", "SEPIA", "GREEN", "DARK"), DesktopNovelTheme.entries.map { it.name })
        DesktopNovelTheme.entries.forEach {
            assertEquals(it, DesktopNovelTheme.valueOf(it.name))
        }
    }

    @Test
    fun `the novel text styles draw with the palette too`() {
        val dark = DesktopNovelSettings(theme = DesktopNovelTheme.DARK).desktopNovelStyles()
        assertEquals(Color(0xFFD7DDE2), dark.body.color)
        assertEquals(Color(0xFFAEB8C0), dark.muted)
        val light = DesktopNovelSettings(theme = DesktopNovelTheme.LIGHT).desktopNovelStyles()
        assertEquals(Color(0xFF4F4032), light.body.color)
    }

    @Test
    fun `the palette is the shared function and not a second copy of the colors`() {
        DesktopNovelTheme.entries.forEach { theme ->
            assertEquals(novelReaderColors(theme.preset, isDarkTheme = theme.palette.isDark), theme.palette)
        }
    }
}
