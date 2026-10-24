package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopTriState
import java.nio.file.Path

/**
 * Android's incognito reader over the real UI: the details page starts a session that records nothing, a normal read
 * afterwards proves the history gate is what kept the row away, and the source settings row stores Android's tri-state
 * under the same key so a shared backup carries the adult rule.
 */
internal object DesktopIncognitoProbe {
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
                // Android's dock offers the incognito read for every work, not only adult ones.
                onNodeWithTag("preview-incognito").assertExists()
                onNodeWithTag("preview-incognito").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER }
                idle()
                check(controller.state.value.readerIncognito) { "the details action starts an incognito session" }
                check(controller.state.value.message == desktopText("reader.incognito"))
                check(runBlocking { session.library.progress(content.id) } == null) {
                    "an incognito session must not write the history"
                }
                runBlocking { controller.backToDetails().join() }
                idle()
                // The same chapter read normally does record, so the row above is missing because of the gate.
                onNodeWithTag("preview-read").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER }
                idle()
                check(!controller.state.value.readerIncognito)
                check(runBlocking { session.library.progress(content.id) }?.chapterId ==
                    controller.state.value.chapter?.id) { "a normal read records the history" }
                runBlocking { controller.backToDetails().join() }
                idle()
                // Android hides the dock item while the session is already incognito; here it is a new session again.
                onNodeWithTag("preview-incognito").assertExists()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("source-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.SOURCE_SETTINGS }
                idle()
                onNodeWithTag("source-setting-incognito-nsfw").performScrollTo().performClick()
                idle()
                onNodeWithTag("source-incognito-dialog").assertExists()
                // Default is Android's ASK, so the row never claims a decision nobody made.
                check(controller.state.value.sourceSettings.incognitoForNsfw == DesktopTriState.ASK)
                onNodeWithTag("source-incognito:ENABLED").performClick()
                idle()
                onNodeWithTag("source-incognito-dialog").assertDoesNotExist()
                check(controller.state.value.sourceSettings.incognitoForNsfw == DesktopTriState.ENABLED)
                check((session.storage.preferences.open("desktop_background").snapshot()["incognito_nsfw"]
                    as? SourcePreferenceValue.Text)?.value == DesktopTriState.ENABLED.name) {
                    "the tri-state is stored as Android stores it, under the same key"
                }
                // The row shows the stored option by name.
                onNodeWithText(AndroidStrings["enable"]).assertExists()
                onNodeWithTag("source-setting-incognito-nsfw").performScrollTo().performClick()
                idle()
                onNodeWithTag("source-incognito:DISABLED").performClick()
                idle()
                check(controller.state.value.sourceSettings.incognitoForNsfw == DesktopTriState.DISABLED)
                onNodeWithTag("source-settings-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.MORE)
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
