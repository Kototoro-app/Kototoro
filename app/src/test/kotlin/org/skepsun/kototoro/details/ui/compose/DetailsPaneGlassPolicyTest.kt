package org.skepsun.kototoro.details.ui.compose

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
                assertFalse(shouldCacheCompactSummary(false, false, false, modern, expansion))
                assertTrue(shouldCacheCompactSummary(true, false, false, modern, expansion))
            }
        }
    }

    @Test
    fun `modern pills and sheet sample the summary independently`() {
        assertTrue(shouldCacheCompactSummary(false, true, false, modernDock = true, expansionProgress = 0f))
        assertFalse(shouldCacheCompactSummary(false, false, true, modernDock = true, expansionProgress = 0.05f))
        assertTrue(shouldCacheCompactSummary(false, false, true, modernDock = true, expansionProgress = 0.5f))
        assertTrue(shouldCacheCompactSummary(false, false, true, modernDock = false, expansionProgress = 0f))
        assertFalse(shouldCacheCompactSummary(false, true, false, modernDock = false, expansionProgress = 0f))
    }

    @Test
    fun `details pane does not apply container press feedback`() {
        assertFalse(DETAILS_PANE_PRESS_FEEDBACK_ENABLED)
    }
}
