package org.skepsun.kototoro.video.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoSizeLabelsTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun `size token baked into the extension title becomes a byte hint`() {
        assertEquals(700 * mib, parseVideoSizeHint("1080p - 700 MB"))
        assertEquals((1.5 * gib).toLong(), parseVideoSizeHint("Server A 1.5 GB"))
        assertEquals((2.5 * gib).toLong(), parseVideoSizeHint("2,5GB"))
        assertEquals(512 * 1024L, parseVideoSizeHint("512 KB"))
        assertEquals(1536L, parseVideoSizeHint("1536 B"))
    }

    @Test
    fun `resolution labels and bare numbers are not size hints`() {
        assertNull(parseVideoSizeHint("1080p"))
        assertNull(parseVideoSizeHint("480p · Mirror 2"))
        assertNull(parseVideoSizeHint("Hardsub 1080p"))
        assertNull(parseVideoSizeHint(""))
    }

    @Test
    fun `unknown sizes format to an empty label`() {
        assertEquals("", formatVideoSize(-1))
    }

    @Test
    fun `formatted sizes stay compact and trim trailing zeros`() {
        assertEquals("0 B", formatVideoSize(0))
        assertEquals("845 B", formatVideoSize(845))
        assertEquals("1 KB", formatVideoSize(1024))
        assertEquals("1.5 KB", formatVideoSize(1536))
        assertEquals("700 MB", formatVideoSize(700 * mib))
        assertEquals("1.5 GB", formatVideoSize((1.5 * gib).toLong()))
        assertEquals("1.17 GB", formatVideoSize(1_200L * mib))
    }
}
