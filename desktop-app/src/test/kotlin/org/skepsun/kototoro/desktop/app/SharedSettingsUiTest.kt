package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class SharedSettingsUiTest {
    @Test
    fun `shared proxy editors cancel reject and correct drafts without losing stored values`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var state by mutableStateOf(ProxySettingsUiState(ProxyMode.DIRECT, "host", "80", "", "secret", true))
            var tests = 0
            setContent {
                DesktopTheme {
                    ProxySettingsContent(state, { state = state.copy(mode = it) }, { field, value ->
                        val accepted = field != ProxyTextField.PORT || value.toIntOrNull() in 1..65535
                        if (accepted) state = when (field) {
                            ProxyTextField.ADDRESS -> state.copy(address = value)
                            ProxyTextField.PORT -> state.copy(port = value)
                            ProxyTextField.USERNAME -> state.copy(username = value)
                            ProxyTextField.PASSWORD -> state.copy(password = value)
                        }
                        accepted
                    }, { tests++ })
                }
            }
            onNodeWithTag("proxy-address").assertIsNotEnabled()
            onNodeWithTag("proxy-test").assertIsNotEnabled()
            onNodeWithTag("proxy-type").performClick()
            onNodeWithTag("proxy-type-option:HTTP").performClick()
            onNodeWithTag("proxy-address").performClick()
            onNodeWithTag("proxy-address-field").performTextReplacement("discard")
            onNodeWithTag("proxy-address-cancel").performClick()
            runOnIdle { assertEquals("host", state.address) }
            onNodeWithTag("proxy-port").performClick()
            onNodeWithTag("proxy-port-field").performTextReplacement("70000")
            onNodeWithTag("proxy-port-save").performClick()
            onNodeWithTag("proxy-port-dialog").assertExists()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-proxy-rejected-$width.png")
            val dialog = onNode(isRoot() and hasAnyDescendant(hasTestTag("proxy-port-dialog")))
            Image.makeFromBitmap(dialog.captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            runOnIdle { assertEquals("80", state.port) }
            onNodeWithTag("proxy-port-field").performTextReplacement("8080")
            onNodeWithTag("proxy-port-save").performClick()
            onNodeWithTag("proxy-port-dialog").assertDoesNotExist()
            runOnIdle { assertEquals("8080", state.port) }
            onNodeWithText("secret").assertDoesNotExist()
            onNodeWithTag("proxy-password").performScrollTo().performClick()
            onNodeWithTag("proxy-password-field").performTextReplacement("new secret")
            onNodeWithTag("proxy-password-save").performClick()
            runOnIdle { assertEquals("new secret", state.password) }
            onNodeWithTag("proxy-test").performScrollTo().performClick()
            runOnIdle { assertEquals(1, tests); state = state.copy(testRunning = true) }
            onNodeWithTag("proxy-test").assertIsNotEnabled()
        }
    }

    @Test
    fun `inline proxy editing permits incomplete input while host validity gates connection tests`() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            var state by mutableStateOf(ProxySettingsUiState(ProxyMode.HTTP, "host", "80", "", "", true))
            setContent {
                DesktopTheme {
                    ProxySettingsContent(state, {}, { field, value ->
                        if (field == ProxyTextField.PORT) state = state.copy(port = value,
                            configured = value.toIntOrNull() in 1..65535)
                        true
                    }, {}, editMode = PreferenceEditMode.INLINE)
                }
            }
            onNodeWithTag("proxy-port-field").performTextReplacement("")
            onNodeWithTag("proxy-test").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("proxy-port-field").performScrollTo().performTextReplacement("8080")
            onNodeWithTag("proxy-test").performScrollTo().assertIsEnabled()
            runOnIdle { assertEquals("8080", state.port) }
        }

    @Test
    fun `shared search preserves cursor through host echoes and reflects external reset`() =
        runDesktopComposeUiTest(width = 500, height = 160) {
            var query by mutableStateOf("abcd")
            var submitted = ""
            setContent { DesktopTheme { DesktopSearchField(query, { submitted = it }, "Search", tag = "query") } }
            val input = onNodeWithTag("query")
            input.performTextInputSelection(TextRange(2))
            input.performTextInput("X")
            runOnIdle { assertEquals("abXcd", submitted); query = submitted }
            input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            input.performTextInput("Y")
            runOnIdle { assertEquals("abXYcd", submitted); query = "replacement" }
            input.assertTextEquals("replacement")
            runOnIdle { query = "" }
            input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            onNodeWithText("Search").assertExists()
            runOnIdle { query = "restored query" }
            input.assertTextEquals("restored query")
            runOnIdle { query = "restored" }
            input.assertTextEquals("restored")
            runOnIdle { query = "" }
            input.performTextInput("pending")
            onNodeWithText("Search").assertDoesNotExist()
            runOnIdle { assertEquals("pending", submitted) }
            input.performTextInput(" more")
            runOnIdle { query = "pending" }
            input.assertTextEquals("pending more")
            runOnIdle { query = submitted }
            input.assertTextEquals("pending more")
        }
}
