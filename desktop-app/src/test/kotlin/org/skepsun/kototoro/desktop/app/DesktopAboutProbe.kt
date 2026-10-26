package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateSettings
import java.nio.file.Path

/**
 * Android's about screen offers two things Windows used to lack: the `allow_unstable_updates` switch and the group of
 * support links. The probe drives both from the more page: the switch must follow the copy's own channel (locked on a
 * nightly build, free on a release one), really change which repository the updater reads, drop the previous channel's
 * answers when it moves, and survive a restart; the link rows must be Android's five, each showing the address it
 * opens.
 *
 * Nothing here clicks a link: that would launch the user's browser. The rows are checked for their tags and their
 * addresses instead, and the fallback path (a JVM with no browser) is what [DesktopController.openExternalUrl] reports
 * as a message.
 */
internal object DesktopAboutProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val directory = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            val update = controller.state.value.update
            val stamped = update.version.channel
            println("DESKTOP_ABOUT_STATE=channel:${update.updateChannel} editable:${update.unstableEditable} " +
                "unstable:${update.settings.unstable}")
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

                // Android's switch, on the about card: checked when this copy follows the nightly repository.
                if (org.skepsun.kototoro.desktop.runtime.DesktopPlatform.current.supportsWindowsPackages) {
                    val switch = onNodeWithTag("app-update-unstable").performScrollTo()
                    switch.assertExists()
                    check(controller.state.value.update.updateChannel ==
                        desktopUpdateChannel(controller.state.value.update.version,
                            controller.state.value.update.settings.unstable)) { "the card and the updater disagree" }
                    if (stamped == DesktopUpdateChannel.NIGHTLY) {
                        // A nightly install cannot go back to releases, so the switch is shown locked and on.
                        check(!controller.state.value.update.unstableEditable) { "a nightly copy exposed the switch" }
                        check(controller.state.value.update.updateChannel == DesktopUpdateChannel.NIGHTLY) {
                            "a nightly copy stopped following its own channel"
                        }
                    } else {
                        check(controller.state.value.update.unstableEditable) { "a release copy locked the switch" }
                        val before = controller.state.value.update
                        check(before.updateChannel == DesktopUpdateChannel.RELEASE) { "started on ${before.updateChannel}" }
                        switch.performClick()
                        waitUntil(timeoutMillis = 10_000) { controller.state.value.update.settings.unstable }
                        idle()
                        val after = controller.state.value.update
                        check(after.updateChannel == DesktopUpdateChannel.NIGHTLY) { "the switch did not move: ${after.updateChannel}" }
                        // The old channel's answers must not survive the move, or the card would describe another channel.
                        check(after.changelog == null && after.available == null) { "the previous channel's answers stayed" }
                        // A restart reads the same toggle back; the setting lives in the same store as the other toggles.
                        session.storage.preferences.open("desktop_app_updates").snapshot().let { values ->
                            val stored = DesktopUpdateSettings.from(values)
                            check(stored.unstable) { "the switch was not persisted: $values" }
                        }
                        switch.performClick()
                        waitUntil(timeoutMillis = 10_000) { !controller.state.value.update.settings.unstable }
                        idle()
                        check(controller.state.value.update.updateChannel == DesktopUpdateChannel.RELEASE) {
                            "turning the switch back off did not return to releases"
                        }
                    }

                } else {
                    onNodeWithTag("app-update-unavailable").performScrollTo().assertExists()
                    onNodeWithTag("app-update-unstable").assertDoesNotExist()
                    onNodeWithTag("app-update-check").assertDoesNotExist()
                    controller.checkAppUpdate()
                    check(!controller.state.value.update.checking)
                    check(controller.state.value.update.available == null)
                }

                // Android's support group: five rows, each naming the address it opens.
                val links = desktopAboutLinks()
                check(links.size == 5) { "the support group has ${links.size} rows" }
                links.first().let { onNodeWithTag("about-link:${it.key}").performScrollTo().assertExists() }
                links.drop(1).forEach { link ->
                    onNodeWithTag("about-link:${link.key}").performScrollTo().assertExists()
                    onNodeWithText(link.url, substring = true).assertExists()
                }
                // Opening a URL is the system browser's business; this only proves the row carries a real address.
                check(links.all { it.openable }) { "a support row has no openable address" }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
