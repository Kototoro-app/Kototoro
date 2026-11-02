package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class DictionaryRulesContentTest {
    @Test
    fun loadingEmptyAndLongListUseTheSameActionsInNarrowAndWideWindows() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var rules by mutableStateOf<List<DictionaryRuleUiModel>?>(null)
            var enabled by mutableStateOf(true)
            var edited: String? = null
            var deleted: String? = null
            var toggled: Pair<String, Boolean>? = null
            setContent {
                DesktopTheme {
                    Surface {
                        DictionaryRulesContent(rules, { edited = it.name }, { rule, checked -> toggled = rule.name to checked },
                            { deleted = it.name }, enabled = enabled)
                    }
                }
            }
            onNodeWithTag("dictionary-rules-loading").assertExists()
            onNodeWithTag("dictionary-rules-empty").assertDoesNotExist()
            runOnIdle { rules = emptyList() }
            onNodeWithTag("dictionary-rules-empty").assertExists()
            runOnIdle { rules = (1..30).map { DictionaryRuleUiModel("Rule " + it, "https://dictionary.example/" + it) } }
            onNodeWithTag("dictionary-rules-list").performScrollToNode(hasTestTag("dictionary-rule:Rule 30"))
            onNodeWithTag("dictionary-rule:Rule 30").performClick()
            onNodeWithTag("dictionary-rule-toggle:Rule 30").performClick()
            onNodeWithTag("dictionary-rule-delete:Rule 30").performClick()
            runOnIdle {
                assertEquals("Rule 30", edited); assertEquals("Rule 30", deleted)
                assertEquals("Rule 30" to false, toggled)
                enabled = false
            }
            onNodeWithTag("dictionary-rule:Rule 30").assertIsNotEnabled()
            onNodeWithTag("dictionary-rule-toggle:Rule 30").assertIsNotEnabled()
            onNodeWithTag("dictionary-rule-delete:Rule 30").assertIsNotEnabled()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-dictionary-list-" + width + ".png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun editorValidatesAndPreservesRawNumericInputWhileHostOwnsDismissal() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 700) {
            var initial by mutableStateOf<DictionaryRuleUiModel?>(null)
            var saved: DictionaryRuleUiModel? = null
            var dismissed = false
            setContent {
                DesktopTheme {
                    Surface {
                        DictionaryRuleEditorDialog(initial, { dismissed = true }, { saved = it })
                    }
                }
            }
            onNodeWithTag("dictionary-rule-save").assertIsNotEnabled()
            onNodeWithTag("dictionary-rule-name").performTextInput("  Local dictionary  ")
            onNodeWithTag("dictionary-rule-url").performTextInput("  https://example.com?q={{key}}  ")
            onNodeWithTag("dictionary-rule-show").performTextInput("  tag.body@all\nsecond line  ")
            onNodeWithTag("dictionary-rule-sort").performScrollTo().performTextClearance()
            onNodeWithTag("dictionary-rule-sort").performTextInput(" invalid ")
            onNodeWithTag("dictionary-rule-enabled").performScrollTo().performClick()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-dictionary-editor-" + width + ".png")
            Image.makeFromBitmap(onNode(isRoot() and hasAnyDescendant(hasTestTag("dictionary-rule-save")))
                .captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("dictionary-rule-save").performClick()
            runOnIdle {
                assertEquals(DictionaryRuleUiModel("Local dictionary", "https://example.com?q={{key}}",
                    "  tag.body@all\nsecond line  ", false, " invalid "), saved)
                assertFalse(dismissed)
                initial = DictionaryRuleUiModel("Another rule", "https://another.example")
            }
            onNodeWithTag("dictionary-rule-name").assertTextContains("Another rule")
            onNodeWithTag("dictionary-rule-cancel").performClick()
            runOnIdle { assertTrue(dismissed); assertEquals("Local dictionary", saved?.name) }
        }
    }

    @Test
    fun busyEditorDisablesFieldsSaveAndDeleteButKeepsCancelAvailable() =
        runDesktopComposeUiTest(width = 380, height = 700) {
            var enabled by mutableStateOf(true)
            var writes = 0
            var closed = false
            setContent {
                DesktopTheme {
                    DictionaryRuleEditorDialog(DictionaryRuleUiModel("Rule", "https://example.com"),
                        { closed = true }, { writes++ }, onDelete = { writes++ }, enabled = enabled)
                }
            }
            runOnIdle { enabled = false }
            onNodeWithTag("dictionary-rule-name").assertIsNotEnabled()
            onNodeWithTag("dictionary-rule-save").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("dictionary-rule-remove").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("dictionary-rule-cancel").performClick()
            runOnIdle { assertTrue(closed); assertEquals(0, writes) }
        }
}
