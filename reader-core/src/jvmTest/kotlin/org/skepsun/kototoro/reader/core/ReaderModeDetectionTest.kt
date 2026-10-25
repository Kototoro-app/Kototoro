package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderModeDetectionTest {

    @Test
    fun `probe page sits at thirty percent of the chapter`() {
        // Android's DetectReaderModeUseCase rounds (pages.size * 0.3): five pages give 1.5, so page 2.
        assertEquals(2, ReaderModeDetection.probePageIndex(5))
        assertEquals(1, ReaderModeDetection.probePageIndex(3))
        assertEquals(3, ReaderModeDetection.probePageIndex(10))
        assertEquals(3, ReaderModeDetection.probePageIndex(11))
        assertEquals(0, ReaderModeDetection.probePageIndex(1))
        assertEquals(0, ReaderModeDetection.probePageIndex(0))
    }

    @Test
    fun `probe page stays inside the chapter`() {
        for (count in 0..40) {
            val index = ReaderModeDetection.probePageIndex(count)
            assertTrue(index >= 0, "count=$count")
            // Only the shortest chapters round up to the page count, and the caller fetches with getOrNull.
            assertTrue(index <= count, "count=$count index=$index")
        }
    }

    @Test
    fun `tall pages are webtoon pages`() {
        assertTrue(ReaderModeDetection.isWebtoonPage(400, 6000))
        assertTrue(ReaderModeDetection.isWebtoonPage(400, 800))
        // Exactly 1.8 is not enough, Android compares with a strict inequality.
        assertFalse(ReaderModeDetection.isWebtoonPage(400, 720))
        assertFalse(ReaderModeDetection.isWebtoonPage(400, 600))
        assertFalse(ReaderModeDetection.isWebtoonPage(900, 300))
    }
}
