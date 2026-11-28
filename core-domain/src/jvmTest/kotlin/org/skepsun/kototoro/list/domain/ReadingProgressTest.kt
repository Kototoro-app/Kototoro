package org.skepsun.kototoro.list.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import kotlin.math.nextDown

class ReadingProgressTest {

    @Test
    fun `stored progress mode names and order stay compatible`() {
        assertEquals(
            listOf("NONE", "PERCENT_READ", "PERCENT_LEFT", "CHAPTERS_READ", "CHAPTERS_LEFT"),
            ProgressIndicatorMode.entries.map { it.name },
        )
    }

    @Test
    fun `chapter progress rounds partially read chapters up`() {
        val progress = ReadingProgress(0.26f, 10, ProgressIndicatorMode.CHAPTERS_READ)
        assertEquals(3, progress.chapters)
        assertEquals(7, progress.chaptersLeft)
        assertEquals(0.74f, progress.percentLeft)
    }

    @Test
    fun `only remaining modes reverse the indicator`() {
        for (mode in ProgressIndicatorMode.entries) {
            assertEquals(mode.name.endsWith("LEFT"), ReadingProgress(0.5f, 10, mode).isReversed())
        }
    }

    @Test
    fun `percentage mode accepts missing chapter counts but chapter mode does not`() {
        assertTrue(ReadingProgress(0f, 0, ProgressIndicatorMode.PERCENT_READ).isValid())
        assertFalse(ReadingProgress(0f, 0, ProgressIndicatorMode.CHAPTERS_READ).isValid())
        assertFalse(ReadingProgress(1f, -1, ProgressIndicatorMode.CHAPTERS_LEFT).isValid())
        assertFalse(ReadingProgress(0.5f, 10, ProgressIndicatorMode.NONE).isValid())
    }

    @Test
    fun `invalid raw progress includes nonfinite values`() {
        for (percent in listOf(-1f, 1.01f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFalse(ReadingProgress.isValid(percent))
            assertFalse(ReadingProgress(percent, 10, ProgressIndicatorMode.PERCENT_READ).isValid())
        }
    }

    @Test
    fun `completion threshold differs from favourites quick filter threshold`() {
        assertFalse(ReadingProgress.isCompleted(0.999f))
        assertFalse(ReadingProgress.isCompleted(0.99998f))
        assertTrue(ReadingProgress.isCompleted(0.99999f))
        assertTrue(ReadingProgress.isCompleted(1f))
    }

    @Test
    fun `percentage label truncates and reports completed histories as one hundred`() {
        assertEquals("42", ReadingProgress.percentToString(0.429f))
        assertEquals("99", ReadingProgress.percentToString(0.99999f.nextDown()))
        assertEquals("100", ReadingProgress.percentToString(0.99999f))
        assertEquals("0", ReadingProgress.percentToString(-1f))
        assertEquals("0", ReadingProgress.percentToString(Float.NaN))
        assertEquals("0", ReadingProgress.percentToString(1.1f))
    }

    @Test
    fun `percentage label is exact at whole percentage boundaries`() {
        assertEquals("0", ReadingProgress.percentToString(0f))
        for (percent in 1..99) {
            val fraction = percent / 100f
            assertEquals(percent.toString(), ReadingProgress.percentToString(fraction))
            assertEquals((percent - 1).toString(), ReadingProgress.percentToString(fraction.nextDown()))
        }
    }

    @Test
    fun `progress fraction fills the indicator at the stored completion threshold`() {
        val unfinished = 0.99999f.nextDown()
        assertEquals(unfinished, ReadingProgress.normalizeCompleted(unfinished))
        assertEquals(1f, ReadingProgress.normalizeCompleted(0.99999f))
        assertEquals(0f, ReadingProgress.normalizeCompleted(0f))
        assertEquals(0.005f, ReadingProgress.normalizeCompleted(0.005f))
    }

    @Test
    fun `history projection normalizes completion before checking validity`() {
        for (percent in listOf(0.99999f, 1f, 1.1f, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, ReadingProgress.fromHistory(percent, 10, ProgressIndicatorMode.PERCENT_READ)?.percent)
        }
        assertEquals(0.42f, ReadingProgress.fromHistory(0.42f, 10, ProgressIndicatorMode.PERCENT_READ)?.percent)
    }

    @Test
    fun `history projection discards disabled invalid and unavailable chapter progress`() {
        assertNull(ReadingProgress.fromHistory(0.5f, 10, ProgressIndicatorMode.NONE))
        assertNull(ReadingProgress.fromHistory(-1f, 10, ProgressIndicatorMode.PERCENT_READ))
        assertNull(ReadingProgress.fromHistory(Float.NaN, 10, ProgressIndicatorMode.PERCENT_READ))
        assertNull(ReadingProgress.fromHistory(1f, 0, ProgressIndicatorMode.CHAPTERS_READ))
    }
}
