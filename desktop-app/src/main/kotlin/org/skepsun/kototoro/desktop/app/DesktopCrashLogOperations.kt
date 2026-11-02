package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.desktop.runtime.DesktopCrashLogFile
import org.skepsun.kototoro.desktop.runtime.DesktopCrashLogs

/** The report whose text the detail dialog is showing, read once when the row was clicked. */
internal data class DesktopCrashLogDetail(val log: DesktopCrashLogFile, val text: String)

/** What the crash-log page draws: the directory once read, and the open detail dialog. */
internal data class DesktopCrashLogsUi(
    /** Null until the page has read the directory; an empty list is a real answer. */
    val files: List<DesktopCrashLogFile>? = null,
    val loading: Boolean = false,
    val detail: DesktopCrashLogDetail? = null,
)

/**
 * Android's crash-log screens (`CrashLogScreen` + `CrashLogDetailScreen`) over [DesktopCrashLogs].
 *
 * The page state is its own flow rather than a [DesktopAppState] field: the list only exists while the page is open,
 * the same way the mirror probe keeps its progress out of the shared state.
 */
internal class DesktopCrashLogOperations(private val scope: CoroutineScope, session: DesktopSession) {
    private val logs = DesktopCrashLogs.of(session.storage.paths, DesktopAppVersion.load().versionName)
    private val mutable = MutableStateFlow(DesktopCrashLogsUi())

    val state: StateFlow<DesktopCrashLogsUi> = mutable.asStateFlow()

    /**
     * Reads the directory on every visit: a renderer or source can crash while the window stays open, and re-entering
     * the page is the only way Android's screen refreshes either.
     */
    fun open() = reload()

    fun reload() {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true) }
        scope.launch {
            val files = logs.files()
            mutable.update { it.copy(files = files, loading = false) }
        }
    }

    fun openDetail(log: DesktopCrashLogFile) {
        scope.launch {
            val text = logs.read(log) ?: return@launch
            mutable.update { it.copy(detail = DesktopCrashLogDetail(log, text)) }
        }
    }

    fun closeDetail() {
        mutable.update { it.copy(detail = null) }
    }

    /** A deleted report cannot stay in the list or on screen, whether it went away here or in a file manager. */
    fun delete(log: DesktopCrashLogFile) {
        scope.launch {
            logs.delete(log)
            mutable.update { it.copy(detail = null) }
            refresh()
        }
    }

    fun clear() {
        scope.launch {
            logs.clear()
            mutable.update { it.copy(detail = null) }
            refresh()
        }
    }

    /** Android's share action: Windows has no share sheet, so the report text becomes the clipboard's content. */
    fun copy(text: String): Boolean = DesktopClipboard.write(text)

    private fun refresh() {
        val files = logs.files()
        mutable.update { it.copy(files = files, loading = false) }
    }
}
