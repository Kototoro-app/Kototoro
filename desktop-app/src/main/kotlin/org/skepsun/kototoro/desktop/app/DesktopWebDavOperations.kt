package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.webdav.WebDavBackupCatalog
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
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
    /** When the last successful upload happened, in epoch millis; `0` means none yet. */
    val lastUploadMillis: Long = 0L,
) {
    val latest: WebDavBackupFile? get() = WebDavBackupCatalog.latest(files)
}

/** What one tick of the automatic backup did, so the caller can decide whether to wait or retry sooner. */
internal enum class DesktopAutoBackup { Disabled, Busy, NotDue, Uploaded, Failed }

/**
 * Android's periodic WebDAV backup for the Windows client: the backups page edits the destination, tests it, uploads
 * the library it would otherwise export to a file, and offers the newest remote file as a preview. The page can also
 * let a timer upload on a saved interval, which is the same rule Android's periodic worker applies: upload once the
 * interval has passed since the last successful upload, never two at a time, and never retry in a tight loop.
 *
 * Merging is not duplicated here: a download goes to a staging file and the existing
 * [DesktopLibraryBackup.preview]/restore flow takes over, so a remote restore and a local restore cannot drift apart.
 */
internal class DesktopWebDavOperations(
    private val storage: DesktopRuntime,
    private val backups: DesktopLibraryBackup,
    /** Replaced in tests; the app builds the Java engine here so no other module owns a Ktor engine. */
    client: DesktopWebDavClient? = null,
    /** Replaced in tests so the timer can be driven instead of waited on. */
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** Shared by the button and the timer: two exports of the same library at once would only race on the server. */
    private val uploadLock = Mutex()

    /** Reads the saved destination once, at start-up; a copy that never configured one simply stays empty. */
    fun restore() {
        val values = preferences.snapshot()
        mutableUi.value = DesktopWebDavUi(
            settings = DesktopWebDavSettings.from(values),
            lastUploadMillis = (values[KEY_LAST_UPLOAD] as? SourcePreferenceValue.Text)?.value?.toLongOrNull() ?: 0L,
        )
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
    suspend fun upload(): WebDavUploadResult = uploadLock.withLock { uploadLocked() }

    /**
     * How long the timer should wait before the next automatic upload, `null` when there is nothing to wait for because
     * the interval is off or the destination is incomplete. `0` means "upload now", which is also what a fresh install
     * does: it has no previous upload to measure the interval against.
     */
    fun autoBackupDelay(now: Long = clock()): Long? {
        val settings = mutableUi.value.settings
        if (!settings.autoBackupEnabled) return null
        val interval = settings.intervalHours.toLong() * 60 * 60 * 1000
        val last = mutableUi.value.lastUploadMillis
        return if (last <= 0L) 0L else (last + interval - now).coerceAtLeast(0L)
    }

    /**
     * One tick of the timer. A busy upload, an interval that has not passed and a disabled timer all leave the remote
     * alone; a failed upload keeps the previous "last upload" so the retry happens after the caller's backoff.
     */
    suspend fun autoBackup(): DesktopAutoBackup {
        if (autoBackupDelay() == null) return DesktopAutoBackup.Disabled
        if (!uploadLock.tryLock()) return DesktopAutoBackup.Busy
        try {
            if (autoBackupDelay() != 0L) return DesktopAutoBackup.NotDue
            return try {
                uploadLocked()
                DesktopAutoBackup.Uploaded
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUi.value = mutableUi.value.copy(error = desktopWebDavError(error))
                DesktopAutoBackup.Failed
            }
        } finally {
            uploadLock.unlock()
        }
    }

    private suspend fun uploadLocked(): WebDavUploadResult {
        val settings = mutableUi.value.settings
        require(settings.configured) { desktopText("webdav.notConfigured") }
        val now = clock()
        Files.createDirectories(staging)
        val file = Files.createTempFile(staging, "upload-", ".zip")
        try {
            Files.deleteIfExists(file)
            backups.export(file)
            val name = client.remoteName(
                dataVersion = DESKTOP_BACKUP_DATA_VERSION,
                timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)),
            )
            client.upload(settings, file, name)
            val size = Files.size(file)
            val removed = runCatching { client.trim(settings, RemoteNamespace.V3) }.getOrDefault(emptyList())
            rememberUpload(now)
            refresh()
            return WebDavUploadResult(name, size, removed)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** A successful upload is the only thing that moves the timer's baseline, so a failed one is retried. */
    private fun rememberUpload(now: Long) {
        preferences.edit(
            SourcePreferenceEdit(changes = mapOf(KEY_LAST_UPLOAD to SourcePreferenceValue.Text(now.toString()))),
        )
        mutableUi.value = mutableUi.value.copy(lastUploadMillis = now, error = null)
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

/** Epoch millis of the last successful upload, kept next to the destination it belongs to. */
private const val KEY_LAST_UPLOAD = "webdav_last_upload_millis"

/** A server's own message is the useful part ("401 Unauthorized"); the exception name is the last resort. */
private fun desktopWebDavError(error: Exception): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
