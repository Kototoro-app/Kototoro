package org.skepsun.kototoro.list.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.list.domain.ReadingProgress

/**
 * The reading-progress label of list / detailed rows follows the user's progress
 * indicator mode, like the cover progress badge does.
 */
class ContentCardStatusTest {

    private fun progress(percent: Float, total: Int = 40, mode: ProgressIndicatorMode) =
        ReadingProgress(percent = percent, totalChapters = total, mode = mode)

    @Test
    fun `no or unstarted progress has no label`() {
        assertNull(contentCardProgressLabel(null))
        assertNull(contentCardProgressLabel(progress(0f, mode = ProgressIndicatorMode.PERCENT_READ)))
        assertNull(contentCardProgressLabel(progress(0.5f, mode = ProgressIndicatorMode.NONE)))
    }

    @Test
    fun `finished works read as completed in every mode`() {
        assertEquals(
            ContentCardProgressLabel.Completed,
            contentCardProgressLabel(progress(1f, mode = ProgressIndicatorMode.CHAPTERS_LEFT)),
        )
    }

    @Test
    fun `label follows the progress indicator mode`() {
        assertEquals(
            ContentCardProgressLabel.PercentRead(42),
            contentCardProgressLabel(progress(0.42f, mode = ProgressIndicatorMode.PERCENT_READ)),
        )
        assertEquals(
            ContentCardProgressLabel.PercentLeft(58),
            contentCardProgressLabel(progress(0.42f, mode = ProgressIndicatorMode.PERCENT_LEFT)),
        )
        assertEquals(
            ContentCardProgressLabel.ChaptersRead(10),
            contentCardProgressLabel(progress(0.25f, mode = ProgressIndicatorMode.CHAPTERS_READ)),
        )
        assertEquals(
            ContentCardProgressLabel.ChaptersLeft(30),
            contentCardProgressLabel(progress(0.25f, mode = ProgressIndicatorMode.CHAPTERS_LEFT)),
        )
    }

    @Test
    fun `chapter modes without a chapter count have no label`() {
        assertNull(contentCardProgressLabel(progress(0.25f, total = 0, mode = ProgressIndicatorMode.CHAPTERS_READ)))
    }
}
