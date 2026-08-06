package org.skepsun.kototoro.video.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TorrentLinkTest {

    @Test
    fun `torrent locators include magnets and torrent files`() {
        assertTrue("magnet:?xt=urn:btih:abc".isTorrentLocator())
        assertTrue("https://example.test/video.TORRENT?token=abc".isTorrentLocator())
        assertFalse("https://example.test/video.mp4?torrent=true".isTorrentLocator())
        assertFalse("thunder://example".isTorrentLocator())
    }

    @Test
    fun `file index is read from query and rejects invalid values`() {
        assertEquals(3, torrentFileIndex("magnet:?xt=urn:btih:abc&index=3"))
        assertEquals(2, torrentFileIndex("https://example.test/video.torrent?INDEX=2&token=x"))
        assertEquals(0, torrentFileIndex("magnet:?xt=urn:btih:abc&index=-1"))
        assertEquals(0, torrentFileIndex("magnet:?xt=urn:btih:abc&index=invalid"))
    }

    @Test
    fun `file selection falls back to first file when index is out of range`() {
        assertEquals(3, selectTorrentFileIndex(requestedIndex = 3, availableIndices = listOf(1, 3)))
        assertEquals(1, selectTorrentFileIndex(requestedIndex = 4, availableIndices = listOf(1, 3)))
        assertEquals(0, selectTorrentFileIndex(requestedIndex = 0, availableIndices = emptyList()))
    }

    @Test
    fun `http byte ranges support playback seeking`() {
        assertEquals(0L..99L, parseTorrentHttpRange("bytes=0-99", totalBytes = 1_000))
        assertEquals(900L..999L, parseTorrentHttpRange("bytes=-100", totalBytes = 1_000))
        assertEquals(500L..999L, parseTorrentHttpRange("bytes=500-", totalBytes = 1_000))
        assertEquals(null, parseTorrentHttpRange("bytes=1000-", totalBytes = 1_000))
        assertEquals(null, parseTorrentHttpRange("bytes=0-1,4-5", totalBytes = 1_000))
    }
}
