package org.skepsun.kototoro.desktop.app

import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.webdav.WebDavBackupCatalog
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.backups.webdav.WebDavEndpoint
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Android's WebDAV settings for the Windows client: where the backups live, who may read them, and how many the
 * server keeps per namespace. Values are stored in one preference namespace, so a copy that is upgraded keeps them.
 */
internal data class DesktopWebDavSettings(
    val serverUrl: String = "",
    val remotePath: String = "",
    val username: String = "",
    val password: String = "",
    /** Files kept per namespace; the shared catalog decides which ones are retired, `0` keeps everything. */
    val maxCount: Int = 3,
    /** Hours between automatic uploads; `0` leaves the timer off and the page purely manual. */
    val intervalHours: Int = 0,
) {
    val configured: Boolean get() = serverUrl.isNotBlank()

    /** The timer only runs for a destination it could actually reach, so a half-filled form stays quiet. */
    val autoBackupEnabled: Boolean get() = intervalHours > 0 && configured

    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_SERVER to SourcePreferenceValue.Text(serverUrl),
        KEY_PATH to SourcePreferenceValue.Text(remotePath),
        KEY_USER to SourcePreferenceValue.Text(username),
        KEY_PASSWORD to SourcePreferenceValue.Text(password),
        KEY_MAX_COUNT to SourcePreferenceValue.Integer(maxCount),
        KEY_INTERVAL to SourcePreferenceValue.Integer(intervalHours),
    )

    companion object {
        const val KEY_SERVER = "webdav_server_url"
        const val KEY_PATH = "webdav_remote_path"
        const val KEY_USER = "webdav_username"
        const val KEY_PASSWORD = "webdav_password"
        const val KEY_MAX_COUNT = "webdav_remote_max_count"
        const val KEY_INTERVAL = "webdav_auto_interval_hours"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopWebDavSettings(
            serverUrl = (values[KEY_SERVER] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            remotePath = (values[KEY_PATH] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            username = (values[KEY_USER] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            password = (values[KEY_PASSWORD] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            maxCount = (values[KEY_MAX_COUNT] as? SourcePreferenceValue.Integer)?.value ?: 3,
            intervalHours = ((values[KEY_INTERVAL] as? SourcePreferenceValue.Integer)?.value ?: 0).coerceAtLeast(0),
        )
    }
}

/** The backup directory of [settings]; a trailing slash is added once here so no caller has to think about it. */
internal fun desktopWebDavEndpoint(settings: DesktopWebDavSettings) = WebDavEndpoint(
    serverUrl = settings.serverUrl,
    remotePath = settings.remotePath,
    authorization = {
        // Android sends credentials only when a user name is set; an empty password is allowed, an empty user is not.
        if (settings.username.isEmpty()) null else "Basic " +
            Base64.getEncoder().encodeToString("${settings.username}:${settings.password}".toByteArray())
    },
)

/**
 * Windows' use of the shared WebDAV protocol: probe, list, upload, download and retire old files.
 *
 * Everything the two platforms must agree on — the request shapes, the remote catalog, which files are "the latest"
 * and which are retired past the retention count — comes from `core-net`. What stays here is what Android does in its
 * own uploader: streaming a local file through Ktor's channel, writing a download to disk, and the remote name.
 */
internal class DesktopWebDavClient(private val client: WebDavBackupClient) {
    suspend fun probe(settings: DesktopWebDavSettings) = client.probe(desktopWebDavEndpoint(settings))

    suspend fun list(settings: DesktopWebDavSettings): List<WebDavBackupFile> =
        client.list(desktopWebDavEndpoint(settings))

    suspend fun upload(settings: DesktopWebDavSettings, file: Path, name: String) {
        val endpoint = desktopWebDavEndpoint(settings)
        client.upload(endpoint, name, Files.size(file)) { channel ->
            withContext(Dispatchers.IO) {
                // A retried PUT needs a fresh stream, so the file is opened inside the writer.
                Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        channel.writeFully(buffer, 0, count)
                    }
                }
            }
        }
    }

    suspend fun download(settings: DesktopWebDavSettings, name: String, destination: Path) {
        val endpoint = desktopWebDavEndpoint(settings)
        try {
            client.download(endpoint, name) { channel ->
                withContext(Dispatchers.IO) {
                    Files.deleteIfExists(destination)
                    Files.newOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            // A refused or interrupted download must not leave a file behind that a restore could pick up.
            withContext(Dispatchers.IO) { Files.deleteIfExists(destination) }
            throw error
        }
    }

    suspend fun delete(settings: DesktopWebDavSettings, name: String) =
        client.delete(desktopWebDavEndpoint(settings), name)

    /**
     * Deletes what the shared retention rule retires, and returns the names it removed.
     *
     * A failed delete is not fatal: the upload already succeeded, so the caller reports it and the next sync retries.
     */
    suspend fun trim(settings: DesktopWebDavSettings, namespace: RemoteNamespace = RemoteNamespace.V3): List<String> {
        if (settings.maxCount <= 0) return emptyList()
        val retired = WebDavBackupCatalog.retired(list(settings), settings.maxCount, namespace)
        val removed = mutableListOf<String>()
        for (file in retired) {
            try {
                delete(settings, file.name)
                removed += file.name
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep going: one stuck file must not hide the upload that already worked.
            }
        }
        return removed
    }

    /** The newest file of any namespace, i.e. the one a restore should offer first. */
    fun latest(files: List<WebDavBackupFile>): WebDavBackupFile? = WebDavBackupCatalog.latest(files)

    fun remoteName(dataVersion: Int, timestamp: String, namespace: RemoteNamespace = RemoteNamespace.V3): String =
        WebDavBackupCatalog.remoteName(dataVersion, namespace, timestamp)
}
