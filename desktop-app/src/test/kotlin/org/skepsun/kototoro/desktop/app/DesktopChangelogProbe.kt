package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.ui.markdown.markdownPlainText
import org.skepsun.kototoro.desktop.runtime.DesktopAppUpdateClient
import org.skepsun.kototoro.desktop.runtime.DesktopPackageVersion
import java.nio.file.Path

/**
 * Android's about screen reaches a changelog that lists every published version with its notes. The Windows host keeps
 * the door on the about card and serves the notes from the same release lookup the updater uses.
 *
 * The probe first asks the channel what it publishes, then checks the page agrees: the same versions in the same order,
 * their notes rendered as text, and the back button returning to the card. Offline (or on a copy without a version
 * stamp) the page's error state is the correct answer, and that is what gets asserted instead.
 */
internal object DesktopChangelogProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            val stamped = controller.state.value.update.version
            val supported = stamped.version != DesktopPackageVersion.UNKNOWN
            val published = if (!supported) Result.failure(IllegalStateException("no version stamp")) else runCatching {
                runBlocking { DesktopAppUpdateClient(stamped.version, stamped.channel,
                    target = requireNotNull(org.skepsun.kototoro.desktop.runtime.DesktopUpdateTarget.current())).releases() }
            }
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

                // Android's about group carries the changelog; the Windows card offers the same door.
                onNodeWithTag("app-update-changelog").performScrollTo().assertExists()
                check(controller.state.value.update.changelog == null) { "notes loaded before the page was opened" }
                onNodeWithTag("app-update-changelog").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.CHANGELOG }
                // The load starts asynchronously, so wait for it to have begun before waiting for it to end.
                val update = controller.state.value.update
                waitUntil(timeoutMillis = 30_000) {
                    update.changelogLoading || controller.state.value.update.changelog != null ||
                        controller.state.value.update.changelogError != null
                }
                waitUntil(timeoutMillis = 30_000) { !controller.state.value.update.changelogLoading }
                idle()

                onNodeWithTag("changelog-back").assertExists()
                onNodeWithTag("changelog-summary").assertExists()
                onNodeWithTag("changelog-version").assertExists()
                // What the page ended up with, and whether that matches what the channel really publishes.
                val loaded = controller.state.value.update.changelog
                val loadError = controller.state.value.update.changelogError
                val releases = published.getOrNull()
                println("DESKTOP_CHANGELOG_STATE=" + when {
                    loadError != null -> "error:$loadError"
                    loaded.isNullOrEmpty() -> "empty"
                    else -> "releases:${loaded.map { it.tag }}"
                })
                when {
                    loadError != null -> {
                        // No stamp, or the channel could not be asked: the page must say so instead of showing a gap.
                        onNodeWithTag("changelog-error").assertExists()
                        check(releases == null) { "the page failed while the channel answered: $loadError" }
                    }

                    loaded.isNullOrEmpty() -> {
                        onNodeWithTag("changelog-empty").assertExists()
                        check(releases.isNullOrEmpty()) {
                            "the page is empty but the channel has ${releases?.size} releases"
                        }
                    }

                    else -> {
                        onNodeWithTag("changelog-list").assertExists()
                        check(releases != null) { "the page has ${loaded.size} releases the channel does not" }
                        val first = releases.first()
                        onNodeWithTag("changelog-release:${first.tag}").performScrollTo().assertExists()
                        check(loaded.map { it.tag } == releases.map { it.tag }) {
                            "page releases: ${loaded.map { it.tag }}, channel: ${releases.map { it.tag }}"
                        }
                        // The notes are the release's own text, rendered (not a placeholder or an empty card).
                        val firstLine = markdownPlainText(first.notes).lineSequence()
                            .map { it.trim() }.firstOrNull { it.isNotEmpty() }
                        if (firstLine != null) {
                            onNodeWithTag("changelog-notes:${first.tag}").assertExists()
                            onNodeWithText(firstLine.take(24), substring = true).assertExists()
                        }
                        // The newest published version is the first card, which is what Android's joined notes assume.
                        check(first.version == releases.maxBy { it.version }.version) {
                            "order: ${releases.map { it.tag }}"
                        }
                    }
                }

                onNodeWithTag("changelog-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("app-update-changelog").performScrollTo().assertExists()
                // Entering the page again reuses the load instead of asking the channel twice.
                onNodeWithTag("app-update-changelog").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.CHANGELOG }
                idle()
                if (releases != null) {
                    check(!controller.state.value.update.changelogLoading) { "a second visit re-fetched the notes" }
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
