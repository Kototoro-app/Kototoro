package org.skepsun.kototoro.video.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TorrentMetadataRegistryTest {

    @Test
    fun `cached metadata is resolved from nyaa magnet`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val metadata = byteArrayOf(1, 2, 3)
        TorrentMetadataRegistry.put(hash, metadata)

        val result = TorrentMetadataRegistry.find(
            "magnet:?xt=urn:btih:$hash&dn=title&tr=https://tracker.example&index=2",
        )

        assertArrayEquals(metadata, result)
    }

    @Test
    fun `info hash parsing supports encoded and case insensitive xt`() {
        assertEquals(
            "ABC123",
            TorrentMetadataRegistry.extractInfoHash("magnet:?XT=urn%3Abtih%3AABC123&dn=title"),
        )
        assertNull(TorrentMetadataRegistry.extractInfoHash("https://example.org/file.torrent"))
    }
}
