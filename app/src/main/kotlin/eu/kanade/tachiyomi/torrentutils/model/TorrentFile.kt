package eu.kanade.tachiyomi.torrentutils.model

import java.net.URLEncoder

data class TorrentFile(
    val path: String,
    val indexFile: Int,
    val size: Long,
    private val torrentHash: String,
    private val trackers: List<String> = emptyList(),
) {
    fun toMagnetURI(): String {
        val encodedTrackers = trackers.joinToString("&tr=") { URLEncoder.encode(it, "UTF-8") }
        val trackerQuery = if (encodedTrackers.isEmpty()) "" else "&tr=$encodedTrackers"
        return "magnet:?xt=urn:btih:$torrentHash$trackerQuery&index=$indexFile"
    }
}
