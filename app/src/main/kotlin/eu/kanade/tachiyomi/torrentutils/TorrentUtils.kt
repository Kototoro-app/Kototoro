package eu.kanade.tachiyomi.torrentutils

import com.frostwire.jlibtorrent.TorrentInfo as JlibTorrentInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.skepsun.kototoro.video.data.TorrentMetadataRegistry
import uy.kohesive.injekt.injectLazy
import java.net.SocketTimeoutException

/** Host implementation of the torrent metadata API exposed by Aniyomi's source-api. */
object TorrentUtils {
    private const val MAX_TORRENT_METADATA_BYTES = 16 * 1024 * 1024

    private val network: NetworkHelper by injectLazy()

    suspend fun getTorrentInfo(url: String, title: String): TorrentInfo = withContext(Dispatchers.IO) {
        require(!url.startsWith("magnet:", ignoreCase = true)) {
            "Magnet metadata lookup is not supported by this compatibility API"
        }
        try {
            val request = Request.Builder().url(url).build()
            val metadata = network.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Torrent metadata request failed: ${response.code}" }
                val declaredLength = response.body.contentLength()
                check(declaredLength < 0 || declaredLength <= MAX_TORRENT_METADATA_BYTES) {
                    "Torrent metadata was too large"
                }
                val bytes = response.body.bytes()
                check(bytes.size <= MAX_TORRENT_METADATA_BYTES) { "Torrent metadata was too large" }
                JlibTorrentInfo(bytes).also { torrentInfo ->
                    TorrentMetadataRegistry.put(torrentInfo.infoHashV1().toHex(), bytes)
                }
            }
            metadata.toAniyomiTorrentInfo(title)
        } catch (error: SocketTimeoutException) {
            throw DeadTorrentException()
        }
    }

    @Deprecated(
        message = "Binary compatibility overload for extensions built against the blocking API",
        level = DeprecationLevel.HIDDEN,
    )
    @JvmName("getTorrentInfo")
    fun blockingShimForGetTorrentInfo(url: String, title: String): TorrentInfo = runBlocking {
        getTorrentInfo(url, title)
    }

    private fun JlibTorrentInfo.toAniyomiTorrentInfo(title: String): TorrentInfo {
        val fileStorage = files()
        val hash = infoHashV1().toHex()
        val trackers = trackers().map { it.url() }.filter { it.isNotBlank() }.distinct()
        val files = (0 until fileStorage.numFiles()).map { index ->
            TorrentFile(
                path = fileStorage.filePath(index),
                indexFile = index,
                size = fileStorage.fileSize(index),
                torrentHash = hash,
                trackers = trackers,
            )
        }
        return TorrentInfo(
            title = title.ifBlank { name() },
            files = files,
            hash = hash,
            size = totalSize(),
            trackers = trackers,
        )
    }
}
