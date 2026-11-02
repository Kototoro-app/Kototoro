package org.skepsun.kototoro.download.domain

import androidx.work.Data
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content

class DownloadStateDataTest {

    private val manga = Content(
        id = 42L,
        title = "Video",
        altTitles = emptySet(),
        url = "/video/42",
        publicUrl = "https://example.org/video/42",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = TestContentSource,
    )

    @Test
    fun `byte counts estimates and current episode survive WorkManager serialization`() {
        val bytes = DownloadByteProgress(3_000_000_000, 6_000_000_000, isTotalEstimated = true)
        val state = DownloadState(
            manga = manga,
            isIndeterminate = false,
            byteProgress = bytes,
            currentChapterTitle = "Episode 652",
        )

        val data = Data.fromByteArray(state.toWorkData().toByteArray())

        assertEquals(bytes, DownloadState.getByteProgress(data))
        assertEquals("Episode 652", DownloadState.getCurrentChapterTitle(data))
        assertEquals(bytes, DownloadState.getByteProgress(state.copy(isCompleted = true).toWorkData()))
        assertEquals(bytes, DownloadState.getByteProgress(state.copy(isPaused = true).toWorkData()))
    }

    @Test
    fun `unknown total remains unknown after serialization`() {
        val state = DownloadState(
            manga = manga,
            isIndeterminate = true,
            byteProgress = DownloadByteProgress(1024),
        )

        val progress = DownloadState.getByteProgress(state.toWorkData())!!

        assertEquals(1024L, progress.downloadedBytes)
        assertNull(progress.totalBytes)
        assertFalse(progress.isTotalEstimated)
    }

    @Test
    fun `old work data and non video tasks have no byte progress`() {
        assertNull(DownloadState.getByteProgress(Data.EMPTY))
        assertNull(DownloadState.getCurrentChapterTitle(Data.EMPTY))
        val state = DownloadState(
            manga = manga,
            isIndeterminate = true,
        )
        assertNull(DownloadState.getByteProgress(state.toWorkData()))
    }
}
