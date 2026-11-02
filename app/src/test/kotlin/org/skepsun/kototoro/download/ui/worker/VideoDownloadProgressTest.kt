package org.skepsun.kototoro.download.ui.worker

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.download.domain.DownloadByteProgress
import java.io.IOException

class VideoDownloadProgressTest {

    @Test
    fun `known length streams report downloaded bytes while copying the video`() = runTest {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val output = Buffer()
        val updates = mutableListOf<DownloadByteProgress>()

        copyVideoWithProgress(Buffer().write(bytes), output, 0, bytes.size.toLong()) { updates += it }

        assertArrayEquals(bytes, output.readByteArray())
        assertEquals(0L, updates.first().downloadedBytes)
        assertTrue(updates.any { it.downloadedBytes in 1 until bytes.size.toLong() })
        assertTrue(updates.all { it.totalBytes == bytes.size.toLong() && !it.isTotalEstimated })
        assertEquals(bytes.size.toLong(), updates.last().downloadedBytes)
    }

    @Test
    fun `unknown length streams update bytes before completion and finish with the actual size`() = runTest {
        val bytes = ByteArray(600 * 1024)
        val updates = mutableListOf<DownloadByteProgress>()

        copyVideoWithProgress(Buffer().write(bytes), Buffer(), 0, null) { updates += it }

        assertTrue(updates.any { it.downloadedBytes > 0 && it.totalBytes == null })
        assertEquals(DownloadByteProgress(bytes.size.toLong(), bytes.size.toLong()), updates.last())
    }

    @Test
    fun `resuming appends bytes and includes the existing file in progress`() = runTest {
        val output = Buffer().writeUtf8("first")
        val updates = mutableListOf<DownloadByteProgress>()

        copyVideoWithProgress(Buffer().writeUtf8("second"), output, 5, 11) { updates += it }

        assertEquals("firstsecond", output.readUtf8())
        assertEquals(DownloadByteProgress(5, 11), updates.first())
        assertEquals(DownloadByteProgress(11, 11), updates.last())
    }

    @Test
    fun `large resumed videos retain 64 bit byte counts`() = runTest {
        val initialBytes = 6L * 1024 * 1024 * 1024
        val updates = mutableListOf<DownloadByteProgress>()

        copyVideoWithProgress(Buffer().writeUtf8("tail"), Buffer(), initialBytes, initialBytes + 4) { updates += it }

        assertEquals(initialBytes, updates.first().downloadedBytes)
        assertEquals(DownloadByteProgress(initialBytes + 4, initialBytes + 4), updates.last())
    }

    @Test
    fun `failed streams do not report a completed size`() = runTest {
        val input = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("Connection lost")
        }.buffer()
        val updates = mutableListOf<DownloadByteProgress>()

        val result = runCatching {
            copyVideoWithProgress(input, Buffer(), 0, null) { updates += it }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(listOf(DownloadByteProgress(0)), updates)
    }

    @Test
    fun `HLS estimates include resumed segments and become exact on completion`() {
        val bytes = 3L * 1024 * 1024 * 1024
        val progress = DownloadByteProgress.forSegments(bytes, completed = 30, total = 100)

        assertEquals(10L * 1024 * 1024 * 1024, progress.totalBytes)
        assertTrue(progress.isTotalEstimated)
        val completed = DownloadByteProgress.forSegments(bytes, completed = 100, total = 100)
        assertEquals(bytes, completed.totalBytes)
        assertFalse(completed.isTotalEstimated)
    }

    @Test
    fun `HLS has no size estimate before any segment completes`() {
        val progress = DownloadByteProgress.forSegments(0, completed = 0, total = 100)

        assertNull(progress.totalBytes)
        assertFalse(progress.isTotalEstimated)
    }
}
