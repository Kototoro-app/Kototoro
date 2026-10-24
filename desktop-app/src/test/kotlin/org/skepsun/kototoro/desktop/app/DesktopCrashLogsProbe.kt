package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopCrashLogs
import java.nio.file.Path

/**
 * Android's crash-log page, end to end: the handler [DesktopSession.open] installs must catch a real uncaught
 * exception and write Android's report format into `logs/crash_logs`; the about card's row must open the list, the
 * newest report first, with the exception line as its subtitle; the detail dialog must show the report and delete it;
 * and "clear" must leave the directory empty.
 */
internal object DesktopCrashLogsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val directory = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            val logs = DesktopCrashLogs.of(session.storage.paths, DesktopAppVersion.load().versionName)
            check(logs.files().isEmpty()) { "the data root already held reports: ${logs.files().map { it.name }}" }

            // The real path: a thread dies with an exception and the JVM's default handler runs the writer.
            val worker = Thread({ error("probe crash from a thread") }, "desktop-crash-probe")
            worker.start()
            worker.join(15_000)
            val written = logs.files()
            check(written.size == 1) { "the installed handler wrote ${written.size} reports" }
            check(written.first().summary.contains("probe crash from a thread")) { written.first().summary }
            println("DESKTOP_CRASH_STATE=files:${written.size} name:${written.first().name} summary:${written.first().summary}")

            // A second report, this time written directly, so the list has two rows to tell apart.
            checkNotNull(logs.write(Thread.currentThread(), IllegalStateException("second report")))
            val reports = logs.files()
            check(reports.size == 2) { "the second report was not written" }

            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()

                // Android's about screen keeps "crash logs" between the update card and the support links.
                onNodeWithTag("app-crash-logs").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.CRASH_LOGS }
                waitUntil(timeoutMillis = 15_000) { controller.crashLogs.value.files?.size == 2 }
                idle()

                reports.forEach { report ->
                    onNodeWithTag("crash-log:${report.name}").assertExists()
                }
                // Newest first: the report written second is the row on top and the one the dialog shows.
                onNodeWithTag("crash-log:${reports.first().name}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.crashLogs.value.detail != null }
                waitForIdle()
                check(controller.crashLogs.value.detail?.text?.contains("second report") == true) {
                    "the dialog showed another report"
                }
                onNodeWithTag("crash-log-text").assertExists()

                // Deleting closes the dialog and takes the file away, not just the row.
                onNodeWithTag("crash-log-delete").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.crashLogs.value.detail == null }
                waitUntil(timeoutMillis = 15_000) { controller.crashLogs.value.files?.size == 1 }
                idle()
                onNodeWithTag("crash-log-text").assertDoesNotExist()
                onNodeWithTag("crash-log:${reports.first().name}").assertDoesNotExist()
                check(logs.files().size == 1) { "the deleted report is still on disk" }

                // "Clear" empties the directory and the page says so instead of showing a lone row.
                onNodeWithTag("crash-logs-clear").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.crashLogs.value.files?.isEmpty() == true }
                idle()
                onNodeWithTag("crash-logs-empty").assertExists()
                check(logs.files().isEmpty()) { "clear left ${logs.files().map { it.name }}" }

                // Back goes to "more", where the row lives.
                onNodeWithTag("crash-logs-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
