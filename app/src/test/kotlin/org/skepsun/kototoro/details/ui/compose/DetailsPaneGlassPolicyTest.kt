package org.skepsun.kototoro.details.ui.compose

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropQuality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailsPaneGlassPolicyTest {

    @Test
    fun `chapter glass respects full quality and retains the cheaper presets`() {
        assertEquals(1f, chapterPanelEffectInputScale(BackdropQuality.QUALITY))
        assertEquals(0.5f, chapterPanelEffectInputScale(BackdropQuality.BALANCED))
        assertEquals(0.5f, chapterPanelEffectInputScale(BackdropQuality.PERFORMANCE))
    }

    @Test
    fun `the compact summary needs a visible glass consumer`() {
        for (modern in listOf(true, false)) {
            for (expansion in listOf(0f, 0.5f, 1f)) {
                assertFalse(shouldCacheCompactSummary(false, false, false, modern, expansion, toolbarGlass = false))
                assertTrue(shouldCacheCompactSummary(true, false, false, modern, expansion, toolbarGlass = false))
                assertEquals(expansion < 1f, shouldCacheCompactSummary(false, false, false, modern, expansion, toolbarGlass = true))
            }
        }
    }

    @Test
    fun `modern pills and sheet sample the summary independently`() {
        assertTrue(shouldCacheCompactSummary(false, true, false, true, 0f, toolbarGlass = false))
        assertFalse(shouldCacheCompactSummary(false, false, true, true, 0.05f, toolbarGlass = false))
        assertTrue(shouldCacheCompactSummary(false, false, true, true, 0.5f, toolbarGlass = false))
        assertTrue(shouldCacheCompactSummary(false, false, true, false, 0f, toolbarGlass = false))
        assertFalse(shouldCacheCompactSummary(false, true, false, false, 0f, toolbarGlass = false))
    }

    @Test
    fun `details pane does not apply container press feedback`() {
        assertFalse(DETAILS_PANE_PRESS_FEEDBACK_ENABLED)
    }

    @Test
    fun `summary card backdrop enables auto compositing and follows glass quality scale`() {
        val base = object : Backdrop {
            override val isCoordinatesDependent: Boolean = true
            override fun DrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?,
            ) = Unit
        }
        val wrapped = summaryCardBackdrop(base)
        assertTrue(wrapped.prefersAutoCompositing)
        assertTrue(wrapped.isCoordinatesDependent)

        val previousScale = BackdropQuality.inputScale
        try {
            BackdropQuality.inputScale = BackdropQuality.BALANCED
            assertEquals(0.5f, wrapped.effectInputScale)

            BackdropQuality.inputScale = BackdropQuality.PERFORMANCE
            assertEquals(0.5f, wrapped.effectInputScale)

            BackdropQuality.inputScale = BackdropQuality.QUALITY
            assertEquals(1f, wrapped.effectInputScale)
        } finally {
            BackdropQuality.inputScale = previousScale
        }
    }

    @Test
    fun `wide summary offscreen cache runs only when live-glass summary cards are active`() {
        assertTrue(shouldCacheWideSummary(hasContentBackdrop = true, showSummaryCards = true, panoramaEnabled = true))
        assertFalse(shouldCacheWideSummary(hasContentBackdrop = false, showSummaryCards = true, panoramaEnabled = true))
        assertFalse(shouldCacheWideSummary(hasContentBackdrop = true, showSummaryCards = false, panoramaEnabled = true))
        assertFalse(shouldCacheWideSummary(hasContentBackdrop = true, showSummaryCards = true, panoramaEnabled = false))
    }
}
