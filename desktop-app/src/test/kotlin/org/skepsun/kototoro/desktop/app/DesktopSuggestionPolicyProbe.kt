package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopSuggestionSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's recommendation worker rules on Windows (§179). Android's `SuggestionsSettingsScreen` has three switches next
 * to the enable row — `only_using_wifi` (WorkManager's `UNMETERED` constraint), `include_disabled_sources` (candidates
 * from every installed source) and `notifications_enable` (announce one stored work). This probe drives them the way a
 * user would (the rows, then the runs they control):
 *
 * - the rows read Android's own titles, stay disabled until recommendations are on, and each tap stores Android's key;
 * - a Wi-Fi-only run on a mobile-only link stores nothing, announces nothing and does not record the run, so the next
 *   wake retries;
 * - `include_disabled_sources` keeps the only source answering after it is switched off, while the enabled-only run
 *   finds nothing;
 * - a run that stores results posts the `suggestion_manga` headline, which the home rail shows above recommendations
 *   until it is dismissed;
 * - the read phase reopens the data root and checks the three keys, the settings model and the reported adapter rule.
 */
internal object DesktopSuggestionPolicyProbe {
    fun run(args: Array<String>) {
        if (args[0] == "suggestionpolicy-read") read(args) else write(args)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun write(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        // Android's UNMETERED constraint reads the platform's connectivity; the probe answers it so a run depends on
        // the rule instead of on whatever link this machine happens to have.
        var unmetered = true
        val controller = DesktopController(session, networkAvailable = { unmetered })
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            val sourceName = session.sourceListings().single().source.name
            // The worker scores candidates against the tags of recent history and favourites, so one favourite seeds it.
            val content = runBlocking { session.sources.getList(sourceName, 0, null, null) }.firstOrNull()
            checkNotNull(content) { "the fixture source returns a list" }
            runBlocking { session.library.addFavourite(content) }
            val preferences = session.storage.preferences.open("desktop_background")
            val settings = controller.state.value.suggestionSettings
            check(!settings.enabled && !settings.wifiOnly && !settings.includeDisabledSources && !settings.notifications)

            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun awaitRun() {
                    // `refreshSuggestions` reports through `suggestionsGenerating` rather than `busy`.
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.suggestionsGenerating }
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
                idle()
                onNodeWithTag("nav:更多").performClick(); idle()
                // Android files the recommendations entry under 服务, not directly under 更多.
                onNodeWithTag("services-open").performScrollTo().performClick(); idle()
                onNodeWithTag("services-suggestions").performScrollTo().performClick(); idle()
                // Android's three worker rows use Android's own titles and summaries, and Android disables them while
                // recommendations themselves are off.
                onNodeWithTag("settings-suggestions-wifi").performScrollTo().assertExists()
                onNodeWithText(AndroidStrings["only_using_wifi"]).assertExists()
                onNodeWithText(AndroidStrings["suggestions_wifi_only_summary"]).assertExists()
                onNodeWithTag("settings-suggestions-disabled-sources").assertExists()
                onNodeWithText(AndroidStrings["include_disabled_sources"]).assertExists()
                onNodeWithTag("settings-suggestions-notifications").assertExists()
                onNodeWithText(AndroidStrings["notifications_enable"]).assertExists()
                onNodeWithText(AndroidStrings["suggestions_info"]).assertExists()
                onNodeWithTag("settings-suggestions-wifi").assertIsNotEnabled()
                onNodeWithTag("settings-suggestions-disabled-sources").assertIsNotEnabled()
                onNodeWithTag("settings-suggestions-notifications").assertIsNotEnabled()
                onNodeWithTag("settings-suggestions-enabled").performClick(); idle()
                check(controller.state.value.suggestionSettings.enabled)
                onNodeWithTag("settings-suggestions-wifi").performScrollTo().performClick(); idle()
                onNodeWithTag("settings-suggestions-disabled-sources").performScrollTo().performClick(); idle()
                onNodeWithTag("settings-suggestions-notifications").performScrollTo().performClick(); idle()
                val flipped = controller.state.value.suggestionSettings
                check(flipped.wifiOnly && flipped.includeDisabledSources && flipped.notifications)
                val stored = preferences.snapshot()
                check((stored["suggestions_wifi"] as? SourcePreferenceValue.Toggle)?.value == true)
                check((stored["suggestions_disabled_sources"] as? SourcePreferenceValue.Toggle)?.value == true)
                check((stored["suggestions_notifications"] as? SourcePreferenceValue.Toggle)?.value == true)
                snapshot("worker-rows")
                // Android's UNMETERED constraint: a wired link counts, loopback and mobile broadband do not.
                check(!hasUnmeteredNetwork(listOf(DesktopNetworkInterface("wwan0", "mobile broadband", true, false))))
                check(!hasUnmeteredNetwork(listOf(DesktopNetworkInterface("lo", null, true, true))))
                check(!hasUnmeteredNetwork(listOf(DesktopNetworkInterface("eth0", "ethernet", false, false))))
                check(hasUnmeteredNetwork(listOf(DesktopNetworkInterface("eth0", "ethernet", true, false))))
                check(hasUnmeteredNetwork(listOf(DesktopNetworkInterface("wwan0", null, true, false),
                    DesktopNetworkInterface("wlan0", "probe wi-fi", true, false))))
                // A Wi-Fi-only run with only a mobile link up is skipped: nothing stored, nothing announced, and the
                // schedule is left alone so the next wake retries instead of waiting the whole interval.
                check(controller.state.value.suggestions.isEmpty()) { "the root starts without suggestions" }
                unmetered = false
                controller.refreshSuggestions(); awaitRun()
                check(controller.state.value.suggestionNotice == null) { "a mobile link skips the run" }
                check(preferences.snapshot()["last_suggestions_run"] == null) { "a skipped run is not recorded" }
                check(controller.state.value.suggestions.isEmpty()) { "a skipped run stores nothing" }
                // On an unmetered link the same run stores, and the notification switch turns it into a headline.
                unmetered = true
                controller.refreshSuggestions(); awaitRun()
                val announced = controller.state.value.suggestionNotice
                check(announced != null) { "the notification switch announces a stored run" }
                val suggested = controller.state.value.suggestions
                check(suggested.isNotEmpty()) { "the fixture source yields suggestions" }
                check(suggested.any { AndroidStrings.format("suggestion_manga", it.title) == announced })
                // Windows has no tray, so the headline sits above the recommendation rail until dismissed.
                onNodeWithTag("nav:主页").performClick(); idle()
                onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("home-suggestion-notice"))
                onNodeWithText(announced.orEmpty()).assertExists()
                snapshot("home-notice")
                onNodeWithTag("home-suggestion-notice-dismiss").performClick(); idle()
                check(controller.state.value.suggestionNotice == null)
                onNodeWithTag("home-suggestion-notice").assertDoesNotExist()
                // include_disabled_sources: with the only source switched off the widened list still answers, while an
                // enabled-only run has no candidates at all.
                controller.setSourceEnabled(sourceName, false).let { runBlocking { it.join() } }; idle()
                check(controller.state.value.enabledSources.isEmpty())
                controller.dismissSuggestionNotice()
                controller.refreshSuggestions(); awaitRun()
                check(controller.state.value.suggestionNotice != null) { "include disabled sources still stores" }
                controller.dismissSuggestionNotice()
                controller.suggestionSettings(controller.state.value.suggestionSettings.copy(includeDisabledSources = false))
                controller.refreshSuggestions(); awaitRun()
                check(controller.state.value.suggestionNotice == null) { "no enabled source, nothing stored" }
                controller.setSourceEnabled(sourceName, true).let { runBlocking { it.join() } }; idle()
                controller.suggestionSettings(controller.state.value.suggestionSettings.copy(includeDisabledSources = true))
                // The page reports the persisted state, so reopening it after the runs is the last look at the rows.
                onNodeWithTag("nav:更多").performClick(); idle()
                // Android files the recommendations entry under 服务, not directly under 更多.
                onNodeWithTag("services-open").performScrollTo().performClick(); idle()
                onNodeWithTag("services-suggestions").performScrollTo().performClick(); idle()
                onNodeWithTag("settings-suggestions-wifi").performScrollTo().assertExists()
                snapshot("worker-rows-after-runs")
            }
        } finally {
            session.close()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun read(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        try {
            val stored = session.storage.preferences.open("desktop_background").snapshot()
            val settings = DesktopSuggestionSettings.from(stored)
            check(settings.enabled) { "recommendations stayed on" }
            check(settings.wifiOnly) { "only_using_wifi was not persisted" }
            check(settings.includeDisabledSources) { "include_disabled_sources was not persisted" }
            check(settings.notifications) { "notifications_enable was not persisted" }
            check((stored["suggestions_wifi"] as? SourcePreferenceValue.Toggle)?.value == true)
            check((stored["suggestions_disabled_sources"] as? SourcePreferenceValue.Toggle)?.value == true)
            check((stored["suggestions_notifications"] as? SourcePreferenceValue.Toggle)?.value == true)
            check(hasUnmeteredNetwork(desktopNetworkInterfaces()) || true) { "the adapter read must not throw" }
            val suggestions = runBlocking { session.suggestions.suggestions() }
            check(suggestions.isNotEmpty()) { "the stored suggestions survived the restart" }
        } finally {
            session.close()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
