package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderBackground
import kotlin.math.max
import kotlin.math.min

class ReaderPanelColorsTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun `light and white backgrounds give a light panel`() {
        assertFalse(ReaderBackground.LIGHT.isDarkPanel(isSystemDark = true))
        assertFalse(ReaderBackground.WHITE.isDarkPanel(isSystemDark = true))
    }

    @Test
    fun `dark and black backgrounds give a dark panel`() {
        assertTrue(ReaderBackground.DARK.isDarkPanel(isSystemDark = false))
        assertTrue(ReaderBackground.BLACK.isDarkPanel(isSystemDark = false))
    }

    @Test
    fun `default and auto backgrounds follow the system`() {
        for (background in listOf(ReaderBackground.DEFAULT, ReaderBackground.AUTO)) {
            assertTrue(background.isDarkPanel(isSystemDark = true))
            assertFalse(background.isDarkPanel(isSystemDark = false))
        }
    }

    @Test
    fun `manga panel text is readable on every background`() {
        for (background in ReaderBackground.entries) {
            for (systemDark in listOf(false, true)) {
                val scheme = if (systemDark) darkColorScheme() else lightColorScheme()
                val colors = mangaReaderPanelColors(background, systemDark, scheme)
                val case = "$background / systemDark=$systemDark"
                assertTrue(contrast(colors.content, colors.container) >= 4.5f, case)
                assertTrue(contrast(colors.content, colors.card) >= 4.5f, case)
                assertTrue(contrast(colors.contentSecondary, colors.card) >= 3f, case)
            }
        }
    }

    @Test
    fun `accent switches to the inverse primary when panel and app themes differ`() {
        val scheme = lightColorScheme()
        assertEquals(scheme.inversePrimary, mangaReaderPanelColors(ReaderBackground.BLACK, false, scheme).accent)
        assertEquals(scheme.primary, mangaReaderPanelColors(ReaderBackground.WHITE, false, scheme).accent)
    }

    @Test
    fun `every surface container of the panel scheme is the card colour`() {
        val colors = mangaReaderPanelColors(ReaderBackground.LIGHT, false, lightColorScheme())
        val scheme = colors.toColorScheme(lightColorScheme())
        listOf(
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).forEach { assertEquals(colors.card, it) }
        assertEquals(colors.container, scheme.surface)
        assertEquals(colors.content, scheme.onSurface)
        assertEquals(colors.accent, scheme.primary)
    }

    @Test
    fun `e-ink colours are pure paper and ink`() {
        val light = mangaReaderPanelColors(ReaderBackground.LIGHT, false, lightColorScheme()).forEInk()
        assertEquals(Color.White, light.container)
        assertEquals(Color.Black, light.content)
        assertEquals(light.container, light.card)
        val dark = mangaReaderPanelColors(ReaderBackground.BLACK, true, darkColorScheme()).forEInk()
        assertEquals(Color.Black, dark.container)
        assertEquals(Color.White, dark.content)
    }
}
