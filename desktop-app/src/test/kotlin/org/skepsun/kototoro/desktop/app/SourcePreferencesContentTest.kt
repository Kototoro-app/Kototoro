package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourcePreferenceChoice
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.sourcePreferenceSections
import org.skepsun.kototoro.core.ui.settings.SourcePreferencesContent

/**
 * The shared per-source settings page: Android's titled sections, a switch for a two-state control and a value row that
 * opens a dialog for everything else. The desktop probe drives the real page through these same tags.
 */
@OptIn(ExperimentalTestApi::class)
class SourcePreferencesContentTest {
    private fun node(
        id: String,
        kind: SourcePreferenceKind,
        title: String = "",
        summary: String = "",
        enabled: Boolean = true,
        choices: List<SourcePreferenceChoice> = emptyList(),
        value: SourcePreferenceValue? = null,
        secret: Boolean = false,
    ) = SourcePreferenceNode(id = id, key = id, title = title, summary = summary, kind = kind,
        enabled = enabled, visible = true, value = value, choices = choices, isSecret = secret)

    private val nodes = listOf(
        node("host:user_agent", SourcePreferenceKind.TEXT, title = "User-Agent", value = SourcePreferenceValue.Text("UA")),
        node("source:toggle", SourcePreferenceKind.TOGGLE, title = "Adult", summary = "Adult sources",
            value = SourcePreferenceValue.Toggle(true)),
        node("source:mirror", SourcePreferenceKind.CHOICE, title = "Mirror",
            choices = listOf(SourcePreferenceChoice("Mirror A", "a"), SourcePreferenceChoice("Mirror B", "b")),
            value = SourcePreferenceValue.Text("b")),
        node("source:kinds", SourcePreferenceKind.MULTI_CHOICE, title = "Kinds",
            choices = listOf(SourcePreferenceChoice("Manga", "m"), SourcePreferenceChoice("Novel", "n")),
            value = SourcePreferenceValue.TextSet(setOf("m", "n"))),
        node("source:token", SourcePreferenceKind.TEXT, title = "Token", secret = true,
            value = SourcePreferenceValue.Text("secret")),
        node("source:login", SourcePreferenceKind.ACTION, title = "Log in"),
        node("source:about", SourcePreferenceKind.INFO, title = "About", summary = "Version 3"),
        node("source:locked", SourcePreferenceKind.TOGGLE, title = "Locked", enabled = false),
        node("source:unknown", SourcePreferenceKind.UNSUPPORTED, title = "WebView"),
        node("group:advanced", SourcePreferenceKind.GROUP, title = "Advanced", choices = emptyList(),
            value = null, summary = "", enabled = true).let { group ->
            group.copy(children = listOf(node("source:custom", SourcePreferenceKind.TEXT, title = "Custom domain")))
        },
    )

    @Composable
    private fun content(
        active: Boolean = true,
        onToggle: (SourcePreferenceNode, Boolean) -> Unit = { _, _ -> },
        onValue: (SourcePreferenceNode) -> Unit = {},
        onAction: (SourcePreferenceNode) -> Unit = {},
    ) {
        DesktopTheme {
            SourcePreferencesContent(
                sections = sourcePreferenceSections(nodes, "Settings"),
                active = active,
                unsupportedText = "Not supported on this host",
                onToggle = onToggle,
                onOpenChoice = onValue,
                onOpenMultiChoice = onValue,
                onOpenText = onValue,
                onAction = onAction,
            )
        }
    }

    @Test
    fun `rows are drawn under Android's section headers with the values Android prints`() {
        runDesktopComposeUiTest(width = 760, height = 1200) {
            setContent { content() }
            onNodeWithText("Settings").assertExists()
            onNodeWithText("Advanced").assertExists()
            onNodeWithText("Custom domain").assertExists()
            listOf("host:user_agent", "source:toggle", "source:mirror", "source:kinds", "source:token",
                "source:login", "source:about", "source:locked", "source:unknown", "source:custom")
                .forEach { onNodeWithTag("preference:$it").assertExists() }
            // A choice shows the entry label, a multi-choice joins the labels, a secret hides its characters.
            onNodeWithText("Mirror B").assertExists()
            onNodeWithText("Manga, Novel").assertExists()
            onNodeWithText("secret").assertDoesNotExist()
            onNodeWithText("•".repeat(6)).assertExists()
            onNodeWithText("Version 3").assertExists()
            // An unsupported control explains itself with the host's wording instead of opening an empty dialog.
            onNodeWithText("Not supported on this host").assertExists()
        }
    }

    @Test
    fun `each row hands its node back to the host that owns the dialog and the write`() {
        var toggled: Pair<String, Boolean>? = null
        var opened: String? = null
        var actioned: String? = null
        runDesktopComposeUiTest(width = 760, height = 1200) {
            setContent {
                content(
                    onToggle = { node, checked -> toggled = node.id to checked },
                    onValue = { node -> opened = node.id },
                    onAction = { node -> actioned = node.id },
                )
            }
            onNodeWithTag("preference:source:toggle").performClick()
            assertEquals("source:toggle" to false, toggled)
            onNodeWithTag("preference:source:mirror").performClick()
            assertEquals("source:mirror", opened)
            opened = null
            onNodeWithTag("preference:source:kinds").performClick()
            assertEquals("source:kinds", opened)
            opened = null
            onNodeWithTag("preference:source:custom").performClick()
            assertEquals("source:custom", opened)
            opened = null
            // An info row is not clickable, so nothing opens for it.
            onNodeWithTag("preference:source:about").performClick()
            assertNull(opened)
            onNodeWithTag("preference:source:login").performClick()
            assertEquals("source:login", actioned)
        }
    }

    @Test
    fun `a busy page or a disabled control does not take clicks`() {
        var toggled: String? = null
        var opened: String? = null
        runDesktopComposeUiTest(width = 760, height = 1200) {
            setContent { content(active = false, onToggle = { node, _ -> toggled = node.id }, onValue = { opened = it.id }) }
            onNodeWithTag("preference:source:mirror").performClick()
            onNodeWithTag("preference:source:toggle").performClick()
            assertNull(opened)
            assertNull(toggled)
        }
        runDesktopComposeUiTest(width = 760, height = 1200) {
            setContent { content(onToggle = { node, _ -> toggled = node.id }) }
            // The source marked this control disabled; the page is otherwise active.
            onNodeWithTag("preference:source:locked").assertIsNotEnabled()
        }
    }
}
