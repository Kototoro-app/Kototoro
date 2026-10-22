package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.webdav.WebDavBackupCatalog
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.desktop.runtime.DESKTOP_BACKUP_DATA_VERSION
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the backups page shows about the WebDAV side: saved settings, reachability and the last listing. */
internal data class DesktopWebDavUi(
    val settings: DesktopWebDavSettings = DesktopWebDavSettings(),
    val reachable: Boolean? = null,
    val files: List<WebDavBackupFile> = emptyList(),
    /** True once a listing was read, so "no files" can be told apart from "not checked yet". */
    val listed: Boolean = false,
    val error: String? = null,
) {
    val latest: WebDavBackupFile? get() = WebDavBackupCatalog.latest(files)
}

/**
 * Android's periodic WebDAV backup for the Windows client, driven by hand: the backups page edits the destination,
 * tests it, uploads the library it would otherwise export to a file, and offers the newest remote file as a preview.
 *
 * Merging is not duplicated here: a download goes to a staging file and the existing
 * [DesktopLibraryBackup.preview]/restore flow takes over, so a remote restore and a local restore cannot drift apart.
 */
internal class DesktopWebDavOperations(
    private val storage: DesktopRuntime,
    private val backups: DesktopLibraryBackup,
    /** Replaced in tests; the app builds the Java engine here so no other module owns a Ktor engine. */
    client: DesktopWebDavClient? = null,
) {
    private val preferences = storage.preferences.open("desktop_webdav")
    private val http = if (client == null) {
        HttpClient(Java) {
            install(HttpTimeout) {
                // A backup is a whole library: generous, but a stalled server must still end the action.
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 10 * 60_000
                requestTimeoutMillis = 30 * 60_000
            }
        }
    } else null
    private val client = client ?: DesktopWebDavClient(
        WebDavBackupClient(requireNotNull(http), requireNotNull(http), ::parseWebDavResources),
    )
    private val staging: Path = storage.paths.root.resolve("webdav")

    private val mutableUi = MutableStateFlow(DesktopWebDavUi())
    val ui = mutableUi.asStateFlow()

    /** Reads the saved destination once, at start-up; a copy that never configured one simply stays empty. */
    fun restore() {
        mutableUi.value = DesktopWebDavUi(settings = DesktopWebDavSettings.from(preferences.snapshot()))
    }

    fun settings(value: DesktopWebDavSettings) {
        val saved = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableUi.value = mutableUi.value.copy(
            settings = value,
            error = if (saved) null else desktopText("webdav.settingsSaveFailed"),
        )
    }

    /** Sends a `PROPFIND` with depth 0 so "reachable" means the directory answers, not that a download works. */
    suspend fun probe() {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        try {
            client.probe(settings)
            mutableUi.value = mutableUi.value.copy(reachable = true, error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUi.value = mutableUi.value.copy(reachable = false, error = desktopWebDavError(error))
        }
    }

    /** Reads the remote directory; a failed refresh keeps the previous listing instead of pretending it is empty. */
    suspend fun refresh(): List<WebDavBackupFile> {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        return try {
            val files = client.list(settings)
            mutableUi.value = mutableUi.value.copy(files = files, listed = true, reachable = true, error = null)
            files
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUi.value = mutableUi.value.copy(reachable = false, error = desktopWebDavError(error))
            throw error
        }
    }

    /**
     * Exports the library to a staging file and uploads it under the shared naming scheme, then applies the retention
     * count. The staging file never outlives the call.
     */
    suspend fun upload(): WebDavUploadResult {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        Files.createDirectories(staging)
        val file = Files.createTempFile(staging, "upload-", ".zip")
        try {
            Files.deleteIfExists(file)
            backups.export(file)
            val name = client.remoteName(
                dataVersion = DESKTOP_BACKUP_DATA_VERSION,
                timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()),
            )
            client.upload(settings, file, name)
            val size = Files.size(file)
            val removed = runCatching { client.trim(settings, RemoteNamespace.V3) }.getOrDefault(emptyList())
            refresh()
            return WebDavUploadResult(name, size, removed)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * Downloads [file] into the staging directory and hands the path to the caller, which previews it through the
     * normal restore flow. The archive copies the file on open, so the staged copy is removed right away.
     */
    suspend fun download(file: WebDavBackupFile): Path {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        Files.createDirectories(staging)
        val destination = staging.resolve(file.name)
        client.download(settings, file.name, destination)
        return destination
    }

    suspend fun delete(file: WebDavBackupFile) {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        client.delete(settings, file.name)
        refresh()
    }

    internal fun close() {
        http?.close()
    }
}

internal data class WebDavUploadResult(val name: String, val size: Long, val removed: List<String>)

/** A server's own message is the useful part ("401 Unauthorized"); the exception name is the last resort. */
private fun desktopWebDavError(error: Exception): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
