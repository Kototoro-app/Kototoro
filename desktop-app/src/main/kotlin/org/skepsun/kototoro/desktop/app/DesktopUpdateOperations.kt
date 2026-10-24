package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopAppUpdate
import org.skepsun.kototoro.desktop.runtime.DesktopAppUpdateClient
import org.skepsun.kototoro.desktop.runtime.DesktopDownloadedUpdate
import org.skepsun.kototoro.desktop.runtime.DesktopInstallLayout
import org.skepsun.kototoro.desktop.runtime.DesktopStagedUpdate
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateException
import org.skepsun.kototoro.desktop.runtime.DesktopUpdatePackage
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateProgress
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateInstaller
import org.skepsun.kototoro.desktop.runtime.locateInstallLayout
import java.nio.file.Files
import java.nio.file.Path

/** What the "about" section of the more page shows about this copy and its update. */
data class DesktopUpdateUi(
    val version: DesktopAppVersion = DesktopAppVersion.UNKNOWN,
    /** False for a copy started outside an installed image (Gradle, an IDE): it has nothing to replace. */
    val supported: Boolean = false,
    val settings: DesktopUpdateSettings = DesktopUpdateSettings(),
    val checking: Boolean = false,
    /** The newest release the last check found, if any. */
    val available: DesktopAppUpdate? = null,
    /** 0..1 while a package downloads. */
    val progress: Float? = null,
    /** A verified package waiting for a restart. */
    val downloaded: DesktopStagedUpdate? = null,
    /** True from the moment the swap script starts until the window closes. */
    val installing: Boolean = false,
    val error: String? = null,
)

/**
 * Android's in-app update flow for the Windows package: check the published releases, download the package, verify
 * it, and replace the installed image on restart.
 *
 * The window never blocks on any of this: [check] and [download] run on the controller's scope and publish progress,
 * and only [install] ends the session — it hands the file swap to a script that waits for this process to exit.
 */
internal class DesktopUpdateOperations(
    private val state: MutableStateFlow<DesktopAppState>,
    private val session: DesktopSession,
    private val scope: CoroutineScope,
) {
    private val preferences = session.storage.preferences.open("desktop_app_updates")
    private val installer = DesktopUpdateInstaller(locateInstallLayout())
    private val mutex = Mutex()
    private var downloaded: DesktopDownloadedUpdate? = null

    val stagingDirectory: Path = session.storage.paths.root.resolve("updates")

    /** Reads the stamped version and the saved toggles once, at start-up. */
    fun restore() {
        val version = DesktopAppVersion.load()
        state.update { it.copy(update = DesktopUpdateUi(
            version = version,
            supported = installer.canSelfUpdate,
            settings = DesktopUpdateSettings.from(preferences.snapshot()),
        )) }
    }

    fun layout(): DesktopInstallLayout? = installer.layout()

    fun settings(value: DesktopUpdateSettings) {
        state.update { it.copy(update = it.update.copy(settings = value, error = null)) }
        if (!preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))) {
            state.update { it.copy(update = it.update.copy(error = desktopText("update.settingsSaveFailed"))) }
        }
    }

    private fun client(): DesktopAppUpdateClient? {
        val version = state.value.update.version.version
        if (version.compareTo(org.skepsun.kototoro.desktop.runtime.DesktopPackageVersion.UNKNOWN) <= 0) return null
        return DesktopAppUpdateClient(version, state.value.update.version.channel,
            mirror = session.githubMirror::rewrite)
    }

    /** Starts a check in the background; [silent] keeps a start-up check from replacing the window's own message. */
    fun check(silent: Boolean = false) {
        if (state.value.update.checking) return
        val client = client() ?: run {
            if (!silent) state.update { it.copy(update = it.update.copy(error = desktopText("update.versionUnknown"))) }
            return
        }
        state.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        scope.launch {
            try {
                val found = client.check()
                state.update { it.copy(update = it.update.copy(checking = false, available = found)) }
                if (found == null) {
                    if (!silent) state.update { it.copy(message = desktopText("update.upToDate")) }
                } else {
                    state.update { it.copy(message = desktopText("update.available", found.version.text)) }
                    if (state.value.update.settings.autoDownload) download()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(update = it.update.copy(checking = false,
                    error = error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    /** Downloads the found package (portable ZIP first; the MSI only when it is the published package). */
    fun download() {
        val update = state.value.update.available ?: return
        if (state.value.update.progress != null) return
        state.update { it.copy(update = it.update.copy(progress = 0f, error = null)) }
        scope.launch {
            try {
                // An MSI installation upgrades through the MSI; a portable one overwrites its image from the ZIP.
                val kind = if (layout()?.kind == org.skepsun.kototoro.desktop.runtime.DesktopInstallKind.MSI &&
                    update.available.packageOf(DesktopUpdatePackage.MSI) != null) {
                    DesktopUpdatePackage.MSI
                } else {
                    DesktopUpdatePackage.PORTABLE_ZIP
                }
                val client = client() ?: throw DesktopUpdateException(desktopText("update.versionUnknown"))
                val received = client.download(update, kind, stagingDirectory, DesktopUpdateProgress { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    // Progress arrives on the download thread; the state flow is safe to update from here.
                    if (fraction != null) state.update { it.copy(update = it.update.copy(progress = fraction)) }
                })
                downloaded = received
                val staged = installer.stage(received, stagingDirectory.resolve("staging"))
                state.update { it.copy(update = it.update.copy(progress = null, downloaded = staged),
                    message = desktopText("update.downloaded", staged.release.version.text)) }
            } catch (error: CancellationException) {
                state.update { it.copy(update = it.update.copy(progress = null)) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(update = it.update.copy(progress = null,
                    error = error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    /**
     * Starts the swap and ends this session. [onClose] is the window's own close path, so the session, the database
     * and the player shut down exactly as they do for a normal quit before the script touches the image.
     */
    fun install(onClose: () -> Unit) {
        val staged = state.value.update.downloaded ?: return
        if (state.value.update.installing) return
        state.update { it.copy(update = it.update.copy(installing = true, error = null)) }
        try {
            val script = installer.apply(staged, stagingDirectory)
            state.update { it.copy(update = it.update.copy(installing = false),
                message = desktopText("update.restarting", script.fileName.toString())) }
            onClose()
        } catch (error: Exception) {
            state.update { it.copy(update = it.update.copy(installing = false,
                error = error.message ?: error.javaClass.simpleName)) }
        }
    }

    /** Applies a downloaded package when the window closes, when the user asked for that. */
    fun installOnExitIfRequested() {
        val ui = state.value.update
        if (!ui.settings.installOnExit || ui.downloaded == null) return
        runCatching { installer.apply(ui.downloaded, stagingDirectory) }
    }

    /** Deletes a downloaded package; the staging directory is inside the data root, away from the image. */
    suspend fun discard() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        mutex.withLock {
            val ui = state.value.update
            ui.downloaded?.packagePath?.let { Files.deleteIfExists(it) }
            downloaded = null
            state.update { it.copy(update = it.update.copy(downloaded = null, available = null, progress = null)) }
        }
    }

    /** Checks in the background when the setting allows it; called by the periodic job loop. */
    fun checkInBackground() {
        if (!state.value.update.settings.autoCheck) return
        if (state.value.update.available != null || state.value.update.downloaded != null) return
        check(silent = true)
    }
}
