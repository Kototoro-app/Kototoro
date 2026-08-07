package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AniyomiTorrentUtilsCompatibilityTest {

    @Test
    fun `legacy blocking torrent metadata API remains available`() {
        val method = TorrentUtils::class.java.declaredMethods.single {
            it.name == "getTorrentInfo" && it.parameterCount == 2
        }

        assertEquals(TorrentInfo::class.java, method.returnType)
        assertTrue(method.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)))
    }

    @Test
    fun `torrent models expose extension compatible accessors`() {
        val file = TorrentFile(
            path = "Season/episode.mkv",
            indexFile = 3,
            size = 42L,
            torrentHash = "abc123",
            trackers = listOf("https://tracker.example/announce"),
        )
        val info = TorrentInfo("Title", listOf(file), "abc123", 42L)

        assertEquals("Season/episode.mkv", file.path)
        assertEquals(3, file.indexFile)
        assertEquals(42L, file.size)
        assertEquals(listOf(file), info.files)
        assertTrue(file.toMagnetURI().contains("&index=3"))
    }
}
