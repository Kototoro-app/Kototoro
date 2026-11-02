package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.storage.StorageUsageItem
import org.skepsun.kototoro.core.ui.storage.StorageUsageRow
import org.skepsun.kototoro.core.ui.storage.StorageUsageTotal
import org.skepsun.kototoro.core.ui.storage.StorageUsageTotals

/**
 * The shared storage breakdown: Android's donut legend rows and its totals row, which the desktop's storage hub draws
 * too. The desktop probe walks the real page; these tests pin the row's four fields, the host-chosen tag, the optional
 * selection Android's donut needs, and the totals row's columns.
 */
@OptIn(ExperimentalTestApi::class)
class StorageUsageContentTest {
    private val images = StorageUsageItem(
        id = "IMAGES",
        label = "Images",
        sizeLabel = "2.9 MB",
        shareLabel = "12.5%",
        color = Color(0xFF4CAF50),
    )

    @Test
    fun `a row prints the label, the size and the share under the host's tag`() {
        runDesktopComposeUiTest(width = 760, height = 400) {
            setContent {
                DesktopTheme { StorageUsageRow(images) }
            }
            onNodeWithTag("storage-usage:IMAGES").assertExists()
            onNodeWithText("Images").assertExists()
            onNodeWithText("2.9 MB").assertExists()
            onNodeWithText("12.5%").assertExists()
        }
    }

    @Test
    fun `a host that selects slices gets a click back, and one that does not gets a plain row`() {
        var clicks = 0
        runDesktopComposeUiTest(width = 760, height = 400) {
            setContent {
                DesktopTheme {
                    StorageUsageRow(images, selected = true, onSelect = { clicks++ })
                }
            }
            onNodeWithTag("storage-usage:IMAGES").performClick()
            assertEquals(1, clicks)
        }
        runDesktopComposeUiTest(width = 760, height = 400) {
            setContent {
                DesktopTheme { StorageUsageRow(images) }
            }
            // No selection callback means no click action, so Android's donut stays the only interactive legend.
            onNodeWithTag("storage-usage:IMAGES").assertHasNoClickAction()
        }
    }

    @Test
    fun `a busy host disables the row instead of dropping it`() {
        var clicks = 0
        runDesktopComposeUiTest(width = 760, height = 400) {
            setContent {
                DesktopTheme {
                    StorageUsageRow(images, enabled = false, onSelect = { clicks++ })
                }
            }
            onNodeWithTag("storage-usage:IMAGES").performClick()
            assertEquals(0, clicks)
        }
    }

    @Test
    fun `the totals row prints as many columns as the host has answers`() {
        runDesktopComposeUiTest(width = 760, height = 400) {
            setContent {
                DesktopTheme {
                    StorageUsageTotals(
                        totals = listOf(
                            StorageUsageTotal("In Kototoro", "421.3 KB"),
                            StorageUsageTotal("Available", "215.23 GB"),
                            StorageUsageTotal("Volume size", "299.94 GB"),
                        ),
                        tag = "storage-usage-summary",
                    )
                }
            }
            onNodeWithTag("storage-usage-summary").assertExists()
            listOf("In Kototoro", "421.3 KB", "Available", "215.23 GB", "Volume size", "299.94 GB").forEach {
                onNodeWithText(it).assertExists()
            }
        }
    }
}
