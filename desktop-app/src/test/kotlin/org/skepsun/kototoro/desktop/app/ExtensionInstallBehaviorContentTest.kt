package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ExtensionInstallBehaviorContentTest {
    private val options = listOf(PreferenceChoice("ASK", "Ask"), PreferenceChoice("ONLY", "Install only"),
        PreferenceChoice("ENABLE", "Install and enable"))
    private val mixed = listOf(ExtensionInstallBehaviorUiItem("JAR", "JAR", "ASK"),
        ExtensionInstallBehaviorUiItem("MIHON", "Mihon", "ONLY"))

    @Test
    fun mixedAndUniformPoliciesUseTheSameBatchAndPerKindEditor() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var items by mutableStateOf(mixed)
            val edits = mutableListOf<Pair<String, String>>()
            var batches = 0
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        ExtensionInstallBehaviorContent(items, options, { type, policy ->
                            edits += type to policy
                            items = items.map { if (it.type == type) it.copy(policy = policy) else it }
                        }, { policy -> batches++; items = items.map { it.copy(policy = policy) } })
                    }
                }
            }
            for (policy in options) onNodeWithTag("install-behavior-batch:" + policy.value).assertIsNotSelected()
            onNodeWithTag("install-behavior-row:JAR").performClick()
            onNodeWithTag("install-behavior-policy-option:ASK").assertIsSelected()
            onNodeWithTag("install-behavior-policy-close").performClick()
            runOnIdle { assertTrue(edits.isEmpty()); assertEquals(0, batches) }
            onNodeWithTag("install-behavior-batch:ONLY").performClick().assertIsSelected()
            runOnIdle { assertTrue(items.all { it.policy == "ONLY" }); assertEquals(1, batches) }
            onNodeWithTag("install-behavior-row:MIHON").performClick()
            onNodeWithTag("install-behavior-policy-option:ENABLE").performClick()
            runOnIdle {
                assertEquals(listOf("MIHON" to "ENABLE"), edits)
                assertEquals("ONLY", items.first().policy)
            }
            for (policy in options) onNodeWithTag("install-behavior-batch:" + policy.value).assertIsNotSelected()
            for (policy in options) {
                onNode(hasText(policy.label) and hasAnyAncestor(hasTestTag("install-behavior-batch:" + policy.value)),
                    useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    it(layouts)
                    assertFalse(layouts.single().hasVisualOverflow)
                }
            }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-install-behavior-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun disabledEditorAlsoDisablesAnOpenChoice() = runDesktopComposeUiTest(width = 380, height = 800) {
        var enabled by mutableStateOf(true)
        var writes = 0
        setContent {
            DesktopTheme { ExtensionInstallBehaviorContent(mixed, options, { _, _ -> writes++ },
                { writes++ }, enabled = enabled) }
        }
        onNodeWithTag("install-behavior-row:JAR").performClick()
        runOnIdle { enabled = false }
        onNodeWithTag("install-behavior-policy-option:ENABLE").assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag("install-behavior-policy-close").performClick()
        onNodeWithTag("install-behavior-batch:ASK").assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag("install-behavior-row:MIHON").assertIsNotEnabled()
        runOnIdle { assertEquals(0, writes) }
    }

    @Test
    fun noSupportedKindsOffersNoBatchWrite() = runDesktopComposeUiTest(width = 380, height = 800) {
        setContent {
            DesktopTheme { ExtensionInstallBehaviorContent(emptyList(), options,
                { _, _ -> error("no kind") }, { error("no supported kinds") }) }
        }
        for (policy in options) {
            onNodeWithTag("install-behavior-batch:" + policy.value).assertIsNotEnabled().assertIsNotSelected()
        }
        onNodeWithTag("install-behavior-row:JAR").assertDoesNotExist()
    }
}
