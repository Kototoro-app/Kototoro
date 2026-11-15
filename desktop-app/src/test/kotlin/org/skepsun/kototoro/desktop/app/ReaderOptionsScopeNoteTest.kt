package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsDetailTabs
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsTab

/**
 * The panel's one-line explanation of what its adjustments change: the reading mode and chapter order stay with the
 * title, everything else is written back as the default. A host that has no such split leaves it blank.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderOptionsScopeNoteTest {
    private val tabs = listOf(
        ReaderOptionsTab("Layout") {
            Box(Modifier.fillMaxSize()) { Text("layout page") }
        },
        ReaderOptionsTab("Display") {
            Box(Modifier.fillMaxSize()) { Text("display page") }
        },
    )

    @Test
    fun `the panel shows the scope note under its pages`() = runDesktopComposeUiTest(width = 900, height = 800) {
        val note = AndroidStrings["reader_options_scope_note"]
        setContent {
            DesktopTheme {
                ReaderOptionsDetailTabs(
                    tabs = tabs,
                    settingsDescription = "Settings",
                    onOpenSettings = {},
                    dragModifier = Modifier,
                    scopeNote = note,
                )
            }
        }
        onNodeWithTag("reader-options-scope-note")
            .assertIsDisplayed()
        onNodeWithTag("reader-options-scope-note").assertTextEquals(note)
    }

    @Test
    fun `a host without a per-title split sees no note`() = runDesktopComposeUiTest(width = 900, height = 800) {
        setContent {
            DesktopTheme {
                ReaderOptionsDetailTabs(
                    tabs = tabs,
                    settingsDescription = "Settings",
                    onOpenSettings = {},
                    dragModifier = Modifier,
                )
            }
        }
        onNodeWithTag("reader-options-scope-note").assertDoesNotExist()
    }
}
