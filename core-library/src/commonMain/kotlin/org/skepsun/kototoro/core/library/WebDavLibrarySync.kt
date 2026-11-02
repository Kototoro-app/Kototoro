package org.skepsun.kototoro.core.library

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.webdav.WebDavBackupCatalog
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.backups.webdav.WebDavEndpoint

/**
 * Android's WebDAV backup settings for the hosts without its uploader (Windows, iOS): where the backups live, who may
 * read them, how many the server keeps per namespace, and the automatic upload interval. Keys are Android's.
 */
data class WebDavSettings(
    val serverUrl: String = "",
    val remotePath: String = "",
    val username: String = "",
    val password: String = "",
    /** Files kept per namespace; the shared catalog decides which ones are retired, `0` keeps everything. */
    val maxCount: Int = 3,
    /** Hours between automatic uploads; `0` leaves automatic backup off. */
    val intervalHours: Int = 0,
) {
    val configured: Boolean get() = serverUrl.isNotBlank()

    /** Automatic backup only runs for a destination it could reach, so a half-filled form stays quiet. */
    val autoBackupEnabled: Boolean get() = intervalHours > 0 && configured

    /** Whether an automatic upload is due, given the last successful one (`null` when there was none). */
    fun isDue(lastUploadAt: Long?, now: Long): Boolean =
        autoBackupEnabled && (lastUploadAt == null || now - lastUploadAt >= intervalHours * 3_600_000L)

    companion object {
        const val KEY_SERVER = "webdav_server_url"
        const val KEY_PATH = "webdav_remote_path"
        const val KEY_USER = "webdav_username"
        const val KEY_PASSWORD = "webdav_password"
        const val KEY_MAX_COUNT = "webdav_remote_max_count"
        const val KEY_INTERVAL = "webdav_auto_interval_hours"
    }
}

/** The backup directory of [settings]; Android sends credentials only when a user name is set. */
@OptIn(ExperimentalEncodingApi::class)
fun webDavEndpoint(settings: WebDavSettings) = WebDavEndpoint(
    serverUrl = settings.serverUrl,
    remotePath = settings.remotePath,
    authorization = {
        if (settings.username.isEmpty()) null
        else "Basic " + Base64.encode("${settings.username}:${settings.password}".encodeToByteArray())
    },
)

/**
 * The library's use of the shared WebDAV protocol: probe, list, stream a backup up or down, and retire old files.
 * Request shapes, the remote catalog, "latest" and retention come from `core-net`; platforms only supply the file
 * streaming (a retried PUT calls [upload]'s writer again, so it must open the file itself).
 */
class WebDavLibrarySync(private val client: WebDavBackupClient) {
    suspend fun probe(settings: WebDavSettings) = client.probe(webDavEndpoint(settings))

    suspend fun list(settings: WebDavSettings): List<WebDavBackupFile> = client.list(webDavEndpoint(settings))

    suspend fun upload(settings: WebDavSettings, name: String, size: Long, write: suspend (ByteWriteChannel) -> Unit) =
        client.upload(webDavEndpoint(settings), name, size, write)

    suspend fun download(settings: WebDavSettings, name: String, read: suspend (ByteReadChannel) -> Unit) =
        client.download(webDavEndpoint(settings), name, read)

    suspend fun delete(settings: WebDavSettings, name: String) = client.delete(webDavEndpoint(settings), name)

    /**
     * Deletes what the shared retention rule retires and returns the removed names. A failed delete is not fatal:
     * the upload already succeeded, and the next upload retries it.
     */
    suspend fun trim(settings: WebDavSettings, namespace: RemoteNamespace = RemoteNamespace.V3): List<String> {
        if (settings.maxCount <= 0) return emptyList()
        val removed = mutableListOf<String>()
        for (file in WebDavBackupCatalog.retired(list(settings), settings.maxCount, namespace)) {
            try {
                delete(settings, file.name)
                removed += file.name
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // One stuck file must not hide the upload that already worked.
            }
        }
        return removed
    }

    /** The newest file of any namespace, the one a restore offers first. */
    fun latest(files: List<WebDavBackupFile>): WebDavBackupFile? = WebDavBackupCatalog.latest(files)

    fun remoteName(dataVersion: Int, timestamp: String, namespace: RemoteNamespace = RemoteNamespace.V3): String =
        WebDavBackupCatalog.remoteName(dataVersion, namespace, timestamp)
}
