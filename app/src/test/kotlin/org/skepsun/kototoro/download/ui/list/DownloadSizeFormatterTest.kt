package org.skepsun.kototoro.download.ui.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.download.domain.DownloadByteProgress
import java.util.Locale

class DownloadSizeFormatterTest {

    @Test
    fun `known sizes display downloaded and total megabytes`() {
        assertEquals(
            "1.5 MB / 6.0 MB",
            formatDownloadSize(DownloadByteProgress(1_572_864, 6_291_456), locale = Locale.US),
        )
    }

    @Test
    fun `estimated totals are marked and identify the current episode in a batch`() {
        assertEquals(
            "1.5 MB / ~6.0 MB · Episode 652",
            formatDownloadSize(
                DownloadByteProgress(1_572_864, 6_291_456, isTotalEstimated = true),
                chapterTitle = "Episode 652",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun `unknown totals show only downloaded bytes and non video tasks show nothing`() {
        assertEquals("0.0 MB", formatDownloadSize(DownloadByteProgress(0), locale = Locale.US))
        assertEquals("1.5 MB", formatDownloadSize(DownloadByteProgress(1_572_864), locale = Locale.US))
        assertNull(formatDownloadSize(null))
    }

    @Test
    fun `megabyte formatting follows the number locale`() {
        assertEquals(
            "1,5 MB / 6,0 MB",
            formatDownloadSize(DownloadByteProgress(1_572_864, 6_291_456), locale = Locale.GERMANY),
        )
    }
}
