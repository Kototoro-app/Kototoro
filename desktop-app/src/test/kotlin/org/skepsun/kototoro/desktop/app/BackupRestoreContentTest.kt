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
import org.skepsun.kototoro.core.ui.backups.BackupRestoreMode
import org.skepsun.kototoro.core.ui.backups.BackupRestoreModeList
import org.skepsun.kototoro.core.ui.backups.BackupRestoreSection
import org.skepsun.kototoro.core.ui.backups.BackupRestoreSectionList

/**
 * The shared restore preview: Android's section checkboxes and restore-mode radios, which the Windows library backup
 * page consumes for its read-only section list.
 */
@OptIn(ExperimentalTestApi::class)
class BackupRestoreContentTest {

    private val sections = listOf(
        BackupRestoreSection(id = "CATEGORIES", title = "Favourite categories", summary = "4 records", checked = true),
        BackupRestoreSection(id = "FAVOURITES", title = "Favourites", summary = "96 records", checked = false),
        BackupRestoreSection(id = "HISTORY", title = "Reading history", checked = true, enabled = false),
    )

    @Composable
    private fun sectionList(
        interactive: Boolean = true,
        enabled: Boolean = true,
        onToggle: (String) -> Unit = {},
    ) {
        DesktopTheme {
            BackupRestoreSectionList(
                sections = sections,
                enabled = enabled,
                onToggle = onToggle,
                interactive = interactive,
            )
        }
    }

    @Test
    fun `every section is a row with its name and entry count`() {
        runDesktopComposeUiTest(width = 720, height = 400) {
            setContent { sectionList() }
            listOf("CATEGORIES", "FAVOURITES", "HISTORY").forEach {
                onNodeWithTag("backup-restore-section:$it").assertExists()
            }
            onNodeWithText("Favourite categories").assertExists()
            onNodeWithText("96 records").assertExists()
            // A section the archive does not carry stays visible but cannot be selected.
            onNodeWithTag("backup-restore-section:HISTORY").assertIsNotEnabled()
        }
    }

    @Test
    fun `a row hands back the id the host maps to its own section`() {
        var toggled: String? = null
        runDesktopComposeUiTest(width = 720, height = 400) {
            setContent { sectionList(onToggle = { toggled = it }) }
            onNodeWithTag("backup-restore-section:FAVOURITES").performClick()
            assertEquals("FAVOURITES", toggled)
        }
    }

    @Test
    fun `a read-only list describes the archive without offering a choice`() {
        var toggled: String? = null
        runDesktopComposeUiTest(width = 720, height = 400) {
            setContent { sectionList(interactive = false, onToggle = { toggled = it }) }
            onNodeWithTag("backup-restore-section:HISTORY").assertExists()
            onNodeWithTag("backup-restore-section:FAVOURITES").performClick()
            assertNull(toggled)
        }
    }

    @Test
    fun `restore modes are radio rows with the explanation under the title`() {
        var selected: String? = null
        runDesktopComposeUiTest(width = 720, height = 400) {
            setContent {
                DesktopTheme {
                    BackupRestoreModeList(
                        modes = listOf(
                            BackupRestoreMode("SNAPSHOT_REPLACE", "Replace existing", "Drops the current library"),
                            BackupRestoreMode("MERGE", "Merge with existing", "Keeps newer entries"),
                        ),
                        selectedId = "SNAPSHOT_REPLACE",
                        enabled = true,
                        onSelect = { selected = it },
                    )
                }
            }
            onNodeWithText("Replace existing").assertExists()
            onNodeWithText("Keeps newer entries").assertExists()
            onNodeWithTag("backup-restore-mode:SNAPSHOT_REPLACE").performClick()
            assertEquals("SNAPSHOT_REPLACE", selected)
            onNodeWithTag("backup-restore-mode:MERGE").performClick()
            assertEquals("MERGE", selected)
        }
    }
}
