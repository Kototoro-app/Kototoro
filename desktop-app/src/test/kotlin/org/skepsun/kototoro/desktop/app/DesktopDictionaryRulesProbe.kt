package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android's dictionary-rule page on Windows, end to end: the reading-settings row opens it; a fresh data root already
 * holds the bundled default rules the build copies out of the Android asset; a rule edited in the dialog is stored in
 * the shared table (name is the primary key, so the old name goes away); the switch writes the same table the lookup
 * reads; export writes Android's JSON and import merges it back; deleting takes the row and the table entry away.
 */
internal object DesktopDictionaryRulesProbe {
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
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("reader-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                idle()
                onNodeWithTag("reader-setting-dictionary-rules").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DICTIONARY_RULES }
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.rules != null }
                idle()

                // The Windows build ships Android's asset, so the page never starts empty.
                val seeded = checkNotNull(controller.dictionaryRules.value.rules) { "the page never read the table" }
                check(seeded.size == 2) { "expected Android's two bundled rules, got ${seeded.map { it.name }}" }
                seeded.forEach { rule -> onNodeWithTag("dictionary-rule:${rule.name}").assertExists() }
                onNodeWithTag("dictionary-rules-empty").assertDoesNotExist()
                println("DESKTOP_DICTIONARY_STATE=seeded:${seeded.joinToString("|") { it.name }}")

                // A new rule: Android keeps save disabled until the name and the URL rule are both there.
                onNodeWithTag("dictionary-rules-add").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.editorOpen }
                waitForIdle()
                onNodeWithTag("dictionary-rule-save").assertIsNotEnabled()
                onNodeWithTag("dictionary-rule-name").performTextInput("本地词典")
                onNodeWithTag("dictionary-rule-url").performTextInput("https://dict.example/s?wd={{key}}")
                onNodeWithTag("dictionary-rule-show").performTextInput("tag.body@all")
                onNodeWithTag("dictionary-rule-save").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.dictionaryRules.value.editorOpen }
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.rules?.size == 3 }
                idle()
                onNodeWithTag("dictionary-rule:本地词典").assertExists()
                val added = checkNotNull(runBlocking { session.dictionaryRules.all() }.firstOrNull { it.name == "本地词典" })
                check(added.showRule == "tag.body@all") { added.showRule }
                check(added.enabled) { "a new rule is enabled like Android's default" }

                // The switch writes the table the lookup reads, without touching the other rules.
                onNodeWithTag("dictionary-rule-toggle:本地词典").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.dictionaryRules.value.rules?.firstOrNull { it.name == "本地词典" }?.enabled == false
                }
                check(runBlocking { session.dictionaryRules.enabled() }.none { it.name == "本地词典" }) {
                    "the disabled rule is still offered to the lookup"
                }

                // Editing the name is an add, exactly like Android: `save` looks the rule up by name, which is the
                // primary key, so the old row stays next to the new one.
                onNodeWithTag("dictionary-rule:本地词典").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.editorOpen }
                waitForIdle()
                onNodeWithTag("dictionary-rule-name").performTextClearance()
                onNodeWithTag("dictionary-rule-name").performTextInput("离线词典")
                onNodeWithTag("dictionary-rule-save").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.dictionaryRules.value.editorOpen }
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.rules?.size == 4 }
                idle()
                onNodeWithTag("dictionary-rule:离线词典").assertExists()
                onNodeWithTag("dictionary-rule:本地词典").assertExists()

                // Export then import: Android's JSON shape, and importing it again changes nothing.
                val target = directory.resolve("kototoro-dictionary-rules.json")
                runBlocking { controller.exportDictionaryRules(target).join() }
                waitUntil(timeoutMillis = 15_000) { Files.exists(target) }
                val exported = Files.readString(target)
                check(exported.contains("离线词典")) { exported }
                // The catalog formats like `String.format`; a `{0}` left over would be visible in both messages.
                val exportedMessage = controller.state.value.message.orEmpty()
                check(exportedMessage.contains(target.fileName.toString())) { exportedMessage }
                check(!exportedMessage.contains("{")) { exportedMessage }
                runBlocking { controller.importDictionaryRules(target).join() }
                idle()
                println(
                    "DESKTOP_DICTIONARY_IMPORT=message:${controller.state.value.message} " +
                        "error:${controller.state.value.error}",
                )
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                val importedMessage = controller.state.value.message.orEmpty()
                check(importedMessage.contains("4")) { importedMessage }
                check(!importedMessage.contains("{")) { importedMessage }
                check(runBlocking { session.dictionaryRules.all() }.size == 4) { "import duplicated a rule" }

                // Deleting removes the row and the table entry.
                onNodeWithTag("dictionary-rule-delete:离线词典").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.dictionaryRules.value.rules?.size == 3 }
                idle()
                onNodeWithTag("dictionary-rule:离线词典").assertDoesNotExist()
                check(runBlocking { session.dictionaryRules.all() }.none { it.name == "离线词典" })

                // Back goes to the reading settings page the row lives on.
                onNodeWithTag("dictionary-rules-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
