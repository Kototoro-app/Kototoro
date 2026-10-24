package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import java.nio.file.Path

/**
 * Android's statistics screen over the real UI: a chapter is opened so the reader records a stop, leaving it closes the
 * stop, and "更多 → 阅读统计" shows that stop with Android's period switch. Clearing asks first and leaves the history
 * alone, which is the difference between the two tables.
 */
internal object DesktopStatsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[2])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                idle()
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.isNotEmpty() }
                idle()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                // A real read: opening the chapter is the first recorded page, leaving the reader closes the stop.
                onNodeWithTag("preview-read").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER }
                idle()
                Thread.sleep(1_200)
                idle()
                runBlocking { controller.backToDetails().join() }
                idle()
                check(runBlocking { session.library.history() }.isNotEmpty()) { "the read recorded the history" }

                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("reading-stats-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.screen == DesktopScreen.READING_STATS && controller.state.value.readingStats != null
                }
                idle()
                val stats = requireNotNull(controller.state.value.readingStats)
                check(stats.works.any { it.content.id == content.id }) { "the stop is listed under its work" }
                check(stats.totalDuration > 0) { "the stop has a duration, not an empty dashboard" }
                onNodeWithTag("stats-total").assertExists()
                onNodeWithTag("stats-work:${content.id}").performScrollTo().assertExists()

                // Android's period switch asks the library for another window, not another row.
                onNodeWithTag("stats-period:DAY").performScrollTo().performClick()
                idle()
                check(controller.state.value.readingStatsPeriod == ReadingStatsPeriod.DAY)
                check(requireNotNull(controller.state.value.readingStats).dailyActivity.size == 1) {
                    "a day of activity for the day period"
                }

                // Clearing asks first, then empties both tables but keeps the history above.
                onNodeWithTag("stats-clear").performScrollTo().performClick()
                idle()
                onNodeWithTag("stats-clear-dialog").assertExists()
                onNodeWithTag("stats-clear-cancel").performClick()
                idle()
                check(requireNotNull(controller.state.value.readingStats).works.isNotEmpty()) {
                    "cancelling keeps the statistics"
                }
                onNodeWithTag("stats-clear").performScrollTo().performClick()
                onNodeWithTag("stats-clear-accept").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readingStats?.works?.isEmpty() == true }
                idle()
                onNodeWithTag("stats-empty").performScrollTo().assertExists()
                check(runBlocking { session.library.history() }.isNotEmpty()) { "clearing statistics keeps the history" }

                onNodeWithTag("stats-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.MORE)
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
