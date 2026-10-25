package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopServicesSettings
import org.skepsun.kototoro.desktop.runtime.desktopReadingTime
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's 服务 page on Windows (§180). Windows used to draw the recommendations and statistics entries straight off
 * 更多; Android keeps both under one 服务 page next to the two switches that decide what the reader records. The probe
 * drives the page the way a user would:
 *
 * - 更多 now has one 服务 row instead of the two old ones, and the page repeats Android's order (推荐, the 阅读统计 split
 *   row whose label opens the statistics screen while its switch records, then 阅读时间估算);
 * - `stats_on` reaches the library immediately: a read while it is off leaves no session row, and switching it back on
 *   records the next one;
 * - `reading_time` decides whether the details page shows the estimate, and the estimate itself follows Android's
 *   twenty-pages-a-chapter rule with its ten seconds a page fallback;
 * - the read phase reopens the data root and checks that both keys, the settings model and the library flag came back.
 */
internal object DesktopServicesProbe {
    fun run(args: Array<String>) {
        if (args[0] == "services-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[2])).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            check(session.library.statsRecording) { "statistics record by default, like Android" }
            val zone = TimeZone.currentSystemDefault()
            fun sessions() = runBlocking {
                session.library.readingStatistics(ReadingStatsPeriod.ALL, zone).sessionCount
            }
            check(sessions() == 0) { "a fresh data root has no session yet" }

            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun snapshot(label: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            val output = Files.createDirectories(Path.of(args[3])).resolve("${args[0]}-$label.png")
                            Files.write(output, it.bytes)
                        }
                    }
                }
                // The wide window draws the details as a preview card; Android's facts live one 详情 tap behind it.
                fun expandDetails() {
                    val expand = onAllNodes(hasTestTag("details-expand")).fetchSemanticsNodes()
                    if (expand.isNotEmpty()) {
                        onNodeWithTag("details-expand").performClick()
                        idle()
                    }
                }
                fun readChapter() {
                    onNodeWithTag("preview-read").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER }
                    idle()
                    Thread.sleep(1_200)
                    idle()
                    runBlocking { controller.backToDetails().join() }
                    idle()
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

                // Android's estimate: twenty pages a chapter at ten seconds a page until the work has statistics of its own.
                // The wide window opens the details as a preview card first, so the full facts need the 详情 button.
                expandDetails()
                val details = requireNotNull(controller.state.value.content) { "the details loaded" }
                val expected = desktopReadingTime(chapterCount = details.chapters.orEmpty().size, percent = null,
                    secondsPerPage = 0)
                check(controller.state.value.detailsReadingTime == expected) {
                    "estimate=${controller.state.value.detailsReadingTime} expected=$expected" +
                        " switches=${controller.state.value.services} chapters=${details.chapters?.size}"
                }
                onNodeWithTag("details-info").performScrollTo().assertExists()
                if (expected == null) {
                    onNodeWithTag("details-reading-time").assertDoesNotExist()
                } else {
                    onNodeWithTag("details-reading-time").performScrollTo().assertExists()
                    onNodeWithTag("details-reading-time").assertTextContains(desktopReadingTimeText(expected),
                        substring = true)
                }

                // §191: Android's related works. This host's Mihon source has no related API, so the page searches the
                // source with the work's title and keeps the hits that name it — the fallback Android uses too.
                waitUntil(timeoutMillis = 20_000) { !controller.state.value.relatedLoading }
                idle()
                check(controller.state.value.relatedWorks.isNotEmpty()) {
                    "related search found nothing for ${details.title} ${details.url}"
                }
                onNodeWithTag("details-related").performScrollTo().assertExists()
                onNodeWithTag("details-related:${controller.state.value.relatedWorks.first().id}").assertExists()
                snapshot("details-related")

                // 更多 keeps one 服务 row; the two entries moved onto the page behind it.
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("reading-stats-open").assertDoesNotExist()
                onNodeWithTag("services-open").performScrollTo().assertExists().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SERVICES }
                idle()
                onNodeWithTag("settings-recommendations-open").performScrollTo().assertExists()
                onNodeWithTag("services-related-manga").assertExists()
                onNodeWithText(AndroidStrings["related_manga"]).assertExists()
                onNodeWithText(AndroidStrings["related_manga_summary"]).assertExists()
                onNodeWithTag("services-stats-open").assertExists()
                onNodeWithText(AndroidStrings["reading_stats"]).assertExists()
                onNodeWithTag("services-reading-time").assertExists()
                onNodeWithText(AndroidStrings["reading_time_estimation"]).assertExists()
                onNodeWithText(AndroidStrings["reading_time_estimation_summary"]).assertExists()
                snapshot("services-page")

                // §191: 相关漫画 is Android's `related_manga`. Off empties the open page's rail and stores the key; back on
                // fills it again without reopening the details (the wrong way round would leave a dead switch behind).
                onNodeWithTag("services-related-manga").performClick(); idle()
                check(!controller.state.value.services.relatedContent) { "the switch turned related works off" }
                check(controller.state.value.relatedWorks.isEmpty()) { "the rail emptied with the switch" }
                check((session.storage.preferences.open("desktop_services").snapshot()["related_manga"]
                    as? SourcePreferenceValue.Toggle)?.value == false) { "related_manga was stored under Android's key" }
                onNodeWithTag("services-related-manga").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    !controller.state.value.relatedLoading && controller.state.value.relatedWorks.isNotEmpty()
                }
                idle()
                snapshot("services-page-related-on")

                // Android's split row: the switch alone records or stops recording; the label opens the statistics screen.
                onNodeWithTag("services-stats-enabled").performClick(); idle()
                check(!controller.state.value.services.statsEnabled) { "the split switch turned statistics off" }
                check(!session.library.statsRecording) { "the library stopped recording at once" }
                check((session.storage.preferences.open("desktop_services").snapshot()["stats_on"]
                    as? SourcePreferenceValue.Toggle)?.value == false) { "stats_on was stored under Android's key" }
                snapshot("services-page-off")

                // Reading with statistics off still saves history, but no session row lands.
                controller.details(content)
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                expandDetails()
                readChapter()
                check(runBlocking { session.library.history() }.isNotEmpty()) { "history is written regardless" }
                check(sessions() == 0) { "statistics off writes no session row" }

                // Switching recording back on records the next stop again.
                controller.servicesSettings(controller.state.value.services.copy(statsEnabled = true))
                idle()
                check(session.library.statsRecording)
                readChapter()
                check(sessions() == 1) { "statistics on records the stop" }

                // The estimate switch hides the row; Android keeps the value next to it in the same page.
                controller.servicesSettings(controller.state.value.services.copy(readingTimeEstimation = false))
                controller.details(content)
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                check(controller.state.value.detailsReadingTime == null) { "the switch stopped the estimate" }
                expandDetails()
                onNodeWithTag("details-info").performScrollTo().assertExists()
                onNodeWithTag("details-reading-time").assertDoesNotExist()

                // The page reports the stored switches after both changes.
                controller.showServices()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SERVICES }
                idle()
                onNodeWithTag("services-stats-enabled").assertExists()
                onNodeWithTag("services-reading-time").performScrollTo().assertExists()
                snapshot("services-page-reading-time-off")

                // Leave related works off so the read phase proves the key survives a restart.
                onNodeWithTag("services-related-manga").performScrollTo().performClick(); idle()
                check(!controller.state.value.services.relatedContent) { "related works were switched back off" }
            }
        } finally {
            session.close()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        try {
            val stored = session.storage.preferences.open("desktop_services").snapshot()
            val settings = DesktopServicesSettings.from(stored)
            check(settings.statsEnabled) { "stats_on came back on" }
            check(!settings.readingTimeEstimation) { "reading_time came back off" }
            check(!settings.relatedContent) { "related_manga came back off" }
            check((stored["stats_on"] as? SourcePreferenceValue.Toggle)?.value == true)
            check((stored["reading_time"] as? SourcePreferenceValue.Toggle)?.value == false)
            check((stored["related_manga"] as? SourcePreferenceValue.Toggle)?.value == false)
            val controller = DesktopController(session)
            check(controller.state.value.services == settings) { "the page reads the stored switches" }
            check(session.library.statsRecording) { "the library follows stats_on at start" }        } finally {
            session.close()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
