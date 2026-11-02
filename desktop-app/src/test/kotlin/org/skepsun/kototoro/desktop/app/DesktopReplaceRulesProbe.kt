package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's replace-rules page on Windows, end to end: the reading-settings row opens it; the empty page offers the
 * Android empty state; a rule saved through the editor appears with Android's `mode · scope · pattern → replacement`
 * summary; the editor's preview applies the pattern before saving; the switch and the enable/disable-all buttons write
 * the same table the novel reader reads; export writes Android's JSON and import merges it back.
 */
internal object DesktopReplaceRulesProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val directory = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                fun openPage() {
                    onNodeWithTag("nav:更多").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                    idle()
                    onNodeWithTag("reader-settings-open").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                    idle()
                    onNodeWithTag("reader-setting-replace-rules").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.REPLACE_RULES }
                    waitUntil(timeoutMillis = 15_000) { controller.replaceRules.value.rules != null }
                    idle()
                }
                idle()
                openPage()

                onNodeWithTag("replace-rules-empty").assertExists()
                check(runBlocking { session.replaceRules.all() }.isEmpty()) { "the fresh data root held rules" }
                // Nothing to enable, disable or export yet: Android greys those actions out on an empty list.
                onNodeWithTag("replace-rules-export").assertIsNotEnabled()
                onNodeWithTag("replace-rules-enable-all").assertIsNotEnabled()

                // A regex rule whose summary must read like Android's, scope and mode included.
                onNodeWithTag("replace-rules-add").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.replaceRules.value.editorOpen }
                waitForIdle()
                onNodeWithTag("replace-rule-name").performTextInput("drop ads")
                onNodeWithTag("replace-rule-pattern").performTextInput("\\n?广告位招租\\n?")
                onNodeWithTag("replace-rule-replacement").performTextInput("")
                // The preview runs the pattern on the sample before anything is stored.
                onNodeWithTag("replace-rule-preview-input").performTextInput("正文\n广告位招租\n结束")
                waitForIdle()
                onNodeWithTag("replace-rule-preview-output").assertExists()
                onNodeWithText("正文结束", substring = true).assertExists()
                onNodeWithTag("replace-rule-save").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.replaceRules.value.editorOpen }
                waitUntil(timeoutMillis = 15_000) { controller.replaceRules.value.rules?.size == 1 }
                idle()

                val saved = checkNotNull(runBlocking { session.replaceRules.all() }.singleOrNull()) { "no rule stored" }
                check(saved.name == "drop ads") { saved.name }
                check(saved.isRegex) { "the editor dropped the regex flag" }
                check(saved.scopeContent) { "the editor dropped the content scope" }
                onNodeWithTag("replace-rule:${saved.id}").assertExists()
                // Android's summary shape: mode · scope · pattern → replacement.
                onNodeWithText("drop ads", substring = true).assertExists()

                // The switch writes the table directly, so a restart would show the same state.
                onNodeWithTag("replace-rule-toggle:${saved.id}").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.replaceRules.value.rules?.singleOrNull()?.isEnabled == false
                }
                check(!runBlocking { session.replaceRules.all() }.single().isEnabled) { "the switch did not persist" }
                onNodeWithTag("replace-rule-toggle:${saved.id}").assertExists()
                onNodeWithTag("replace-rules-enable-all").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.replaceRules.value.rules?.singleOrNull()?.isEnabled == true
                }

                // Export then import: the exported document is the shared Legado shape and re-adds what was cleared.
                val target = directory.resolve("kototoro-replace-rules.json")
                runBlocking { controller.exportReplaceRules(target).join() }
                waitUntil(timeoutMillis = 15_000) { java.nio.file.Files.exists(target) }
                check(java.nio.file.Files.readString(target).contains("drop ads")) { "the export lost the rule" }
                // The catalog formats like `String.format`, so a `{0}` left in the message would show up here.
                val exportedMessage = controller.state.value.message.orEmpty()
                check(exportedMessage.contains(target.fileName.toString())) { exportedMessage }
                check(!exportedMessage.contains("{")) { exportedMessage }
                runBlocking { controller.importReplaceRules(target).join() }
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.message?.let { it.contains("1") } == true
                }
                check(runBlocking { session.replaceRules.all() }.size == 1) { "import duplicated the rule" }

                // Back returns to the reading settings page the row lives on.
                onNodeWithTag("replace-rules-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
