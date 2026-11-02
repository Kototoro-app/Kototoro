package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleEditorDialog
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleUiModel
import org.skepsun.kototoro.core.ui.settings.ReplaceRulesContent
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared replace-rule page on its own: the loading/empty states, Android's grouping and summary line, the action
 * states an empty table greys out, and the editor's draft/validation/preview contract the hosts rely on.
 */
@OptIn(ExperimentalTestApi::class)
class ReplaceRulesContentTest {
    @Test
    fun loadingEmptyAndGroupedRulesUseAndroidActionsInNarrowAndWideWindows() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 900) {
            var rules by mutableStateOf<List<ReplaceRuleUiModel>?>(null)
            var enabled by mutableStateOf(true)
            var added = 0
            var imported = 0
            var exported = 0
            var edited: Long? = null
            var toggled: Pair<Long, Boolean>? = null
            var allEnabled: Boolean? = null
            setContent {
                DesktopTheme {
                    Surface {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ReplaceRulesContent(
                                rules = rules,
                                onAdd = { added++ },
                                onEdit = { edited = it.id },
                                onToggle = { rule, checked -> toggled = rule.id to checked },
                                onSetAllEnabled = { allEnabled = it },
                                onImport = { imported++ },
                                onExport = { exported++ },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
            // Not read yet is not the same answer as "no rules": only the former shows the loading row.
            onNodeWithTag("replace-rules-loading").assertExists()
            onNodeWithTag("replace-rules-empty").assertDoesNotExist()
            onNodeWithTag("replace-rules-export").assertIsNotEnabled()
            onNodeWithTag("replace-rules-enable-all").assertIsNotEnabled()
            onNodeWithTag("replace-rules-disable-all").assertIsNotEnabled()

            runOnIdle { rules = emptyList() }
            onNodeWithTag("replace-rules-empty").assertExists()
            onNodeWithTag("replace-rules-add").performClick()
            onNodeWithTag("replace-rules-import").performClick()
            runOnIdle {
                assertEquals(1, added)
                assertEquals(1, imported)
                assertEquals(0, exported)
                assertNull(allEnabled)
            }

            runOnIdle {
                rules = listOf(
                    ReplaceRuleUiModel(id = 1, name = "drop ads", pattern = "ads", replacement = "",
                        scopeTitle = true, scopeContent = true),
                    ReplaceRuleUiModel(id = 2, name = "squeeze", group = " cleanup ", pattern = "\\s+",
                        replacement = " ", isRegex = false, isEnabled = false),
                )
            }
            // Android's group headings: the blank group of the first rule is named, the trimmed second one keeps its name.
            onNodeWithText(AndroidStrings["replace_rule_group_default"]).assertExists()
            onNodeWithText("cleanup").assertExists()
            onNodeWithText("drop ads").assertExists()
            // Android's summary shape, in the UI language instead of the page's old English-only tokens.
            onNodeWithText(
                AndroidStrings["replace_rule_mode_text"] + " · " + AndroidStrings["novel_reader_replace_scope_content"],
                substring = true,
            ).assertExists()
            onNodeWithText(
                AndroidStrings["replace_rule_mode_regex"] + " · " +
                    AndroidStrings["novel_reader_replace_scope_title_content"],
                substring = true,
            ).assertExists()

            onNodeWithTag("replace-rule:1").performClick()
            runOnIdle { assertEquals(1L, edited) }
            onNodeWithTag("replace-rule-toggle:2").performClick()
            runOnIdle { assertEquals(2L to true, toggled) }
            onNodeWithTag("replace-rules-export").performClick()
            onNodeWithTag("replace-rules-enable-all").performClick()
            onNodeWithTag("replace-rules-disable-all").performClick()
            runOnIdle {
                assertEquals(1, exported)
                assertEquals(false, allEnabled)
            }

            // A long table still reaches its last rule through the host's scroll container.
            runOnIdle {
                rules = (1..30).map {
                    ReplaceRuleUiModel(id = it.toLong(), name = "Rule " + it, pattern = "pattern " + it)
                }
            }
            onNodeWithTag("replace-rule:30").performScrollTo()
            onNodeWithTag("replace-rule:30").assertExists()
            onNodeWithTag("replace-rule:30").performClick()
            runOnIdle { assertEquals(30L, edited) }

            runOnIdle { enabled = false }
            onNodeWithTag("replace-rules-add").assertIsNotEnabled()
            onNodeWithTag("replace-rules-import").assertIsNotEnabled()
            onNodeWithTag("replace-rules-export").assertIsNotEnabled()
            onNodeWithTag("replace-rule-toggle:30").assertIsNotEnabled()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-replace-rules-list-" + width + ".png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun editorValidatesPreviewsAndKeepsAndroidDraftRules() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var initial by mutableStateOf<ReplaceRuleUiModel?>(null)
            var saved: ReplaceRuleUiModel? = null
            var deleted = 0
            var dismissed = false
            setContent {
                DesktopTheme {
                    Surface {
                        ReplaceRuleEditorDialog(
                            initial = initial,
                            onDismiss = { dismissed = true },
                            onSave = { saved = it },
                            // The hosts answer these from the stored rule; a trailing "|" is Android's invalid pattern.
                            isValid = { it.pattern.isNotBlank() && !it.pattern.endsWith("|") },
                            preview = { rule, text ->
                                if (rule.isRegex) text.replace(Regex(rule.pattern), rule.replacement)
                                else text.replace(rule.pattern, rule.replacement)
                            },
                            onDelete = initial?.let { { deleted++ } },
                        )
                    }
                }
            }
            onNodeWithTag("replace-rule-save").assertIsNotEnabled()
            onNodeWithTag("replace-rule-name").performTextInput("drop ads")
            onNodeWithTag("replace-rule-pattern").performTextInput("广告\\d*")
            onNodeWithTag("replace-rule-replacement").performTextInput("")
            // Android keeps digits only, eight at most, for both numeric fields.
            onNodeWithTag("replace-rule-order").performScrollTo().performTextClearance()
            onNodeWithTag("replace-rule-order").performTextInput("1a2")
            onNodeWithTag("replace-rule-timeout").performScrollTo().performTextClearance()
            onNodeWithTag("replace-rule-timeout").performTextInput("9x9")
            onNodeWithTag("replace-rule-scope").performScrollTo().performTextInput(" source ")
            // The preview runs before anything is stored.
            onNodeWithTag("replace-rule-preview-input").performScrollTo().performTextInput("正文广告12结束")
            onNodeWithTag("replace-rule-preview-output").assertTextContains("正文结束", substring = true)
            // Android's scope checkboxes decide whether a save button is enabled at all.
            onNodeWithTag("replace-rule-scope-content").performScrollTo().performClick()
            onNodeWithTag("replace-rule-save").assertIsNotEnabled()
            onNodeWithTag("replace-rule-scope-content").performScrollTo().performClick()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-replace-rules-editor-" + width + ".png")
            // The dialog is its own Compose root, so the screenshot has to name the root that carries the editor.
            Image.makeFromBitmap(onNode(isRoot() and hasAnyDescendant(hasTestTag("replace-rule-save")))
                .captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("replace-rule-save").performClick()
            runOnIdle {
                assertEquals(
                    ReplaceRuleUiModel(name = "drop ads", pattern = "广告\\d*", replacement = "",
                        scopeFilter = " source ", order = 12, timeoutMillisecond = 99),
                    saved,
                )
                // The editor never closes itself; the host decides when a successful save leaves the dialog.
                assertFalse(dismissed)
            }

            // A stored rule re-seeds the draft, keeps its identity, and offers delete.
            runOnIdle { initial = ReplaceRuleUiModel(id = 7, name = "keep", pattern = "bad|") }
            onNodeWithTag("replace-rule-name").assertTextContains("keep")
            onNodeWithTag("replace-rule-invalid").assertExists()
            onNodeWithTag("replace-rule-save").assertIsNotEnabled()
            onNodeWithTag("replace-rule-delete").performClick()
            onNodeWithTag("replace-rule-cancel").performClick()
            runOnIdle {
                assertEquals(1, deleted)
                assertTrue(dismissed)
                assertEquals("drop ads", saved?.name)
            }
        }
    }
}
