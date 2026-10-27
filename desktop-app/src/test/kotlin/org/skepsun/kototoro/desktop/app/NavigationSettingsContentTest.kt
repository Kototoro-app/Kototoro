package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.MainNavigationRules
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsContent
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsItem
import java.nio.file.Files
import java.nio.file.Path

/** Both input styles edit the same order; dialog cancellation and disabled states must never write preferences. */
@OptIn(ExperimentalTestApi::class)
class NavigationSettingsContentTest {
    private val destinations = listOf("HOME", "HISTORY", "FAVOURITES", "BROWSE", "FEED", "SUGGESTIONS")
        .map { NavigationSettingsItem(it, it, if (it == "SUGGESTIONS") "Unavailable" else null) }

    @Test
    fun `buttons picker and cap work in narrow and wide windows`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var items by mutableStateOf(destinations.take(5))
            setContent {
                DesktopTheme {
                    NavigationSettingsContent(
                        configuredItems = items,
                        availableItems = destinations.filter { candidate -> items.none { it.id == candidate.id } },
                        canShowAddAction = true,
                        canAddAction = MainNavigationRules.canAdd(items),
                        onAddItem = { id -> items = MainNavigationRules.add(items, destinations.first { it.id == id }) },
                        onRemoveItem = { id -> items = items.filterNot { it.id == id } },
                        onReorder = { from, to -> items = MainNavigationRules.move(items, from, to) },
                        showMoveButtons = true,
                    )
                }
            }
            onNodeWithTag("nav-config-add").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("nav-config-up:HOME").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("nav-config-down:FEED").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("nav-config-down:HOME").performScrollTo().performClick()
            runOnIdle { assertEquals("HISTORY", items.first().id) }
            onNodeWithTag("nav-config-up:HOME").performClick()
            runOnIdle { assertEquals("HOME", items.first().id) }
            onNodeWithTag("nav-config-remove:HOME").performClick()
            onNodeWithTag("nav-config-add").performScrollTo().performClick()
            onNodeWithTag("nav-config-add-option:SUGGESTIONS").assertExists()
            onNodeWithTag("nav-config-add-cancel").performClick()
            runOnIdle { assertEquals(4, items.size) }
            onNodeWithTag("nav-config-add").performClick()
            onNodeWithTag("nav-config-add-option:HOME").performClick()
            runOnIdle { assertEquals("HOME", items.last().id) }
            onNodeWithTag("nav-config-add-dialog").assertDoesNotExist()
            onNodeWithTag("nav-config-add").performScrollTo().assertIsNotEnabled()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-navigation-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun `touch drag and accessibility reorder commit to the host`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var items by mutableStateOf(destinations.take(3))
            var commits = 0
            setContent {
                DesktopTheme {
                    NavigationSettingsContent(items, emptyList(), false, false, {}, {}, { from, to ->
                        items = MainNavigationRules.move(items, from, to)
                        commits++
                    })
                }
            }
            val rowHeight = onNodeWithTag("nav-config-item:HOME").fetchSemanticsNode().boundsInRoot.height
            onNodeWithTag("nav-config-reorder:HOME").performTouchInput {
                swipe(center, center + Offset(0f, rowHeight + 16f), durationMillis = 800)
            }
            waitUntil { commits > 0 }
            runOnIdle { assertEquals(listOf("HISTORY", "HOME", "FAVOURITES"), items.map { it.id }) }
            val actions = onNodeWithTag("nav-config-reorder:HOME").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
            runOnIdle { assertTrue(actions.first().action()) }
            runOnIdle {
                assertEquals(listOf("HOME", "HISTORY", "FAVOURITES"), items.map { it.id })
                assertEquals(2, commits)
            }
        }

    @Test
    fun `busy state disables actions including an already open picker`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    NavigationSettingsContent(destinations.take(1), destinations.drop(1), true, true,
                        { writes++ }, { writes++ }, { _, _ -> writes++ }, enabled = enabled, showMoveButtons = true)
                }
            }
            onNodeWithTag("nav-config-add").performClick()
            runOnIdle { enabled = false }
            onNodeWithTag("nav-config-add-option:HISTORY").assertIsNotEnabled()
            onNodeWithTag("nav-config-add-cancel").performClick()
            onNodeWithTag("nav-config-add").assertIsNotEnabled()
            onNodeWithTag("nav-config-remove:HOME").assertIsNotEnabled()
            onNodeWithTag("nav-config-up:HOME").assertIsNotEnabled()
            runOnIdle { assertEquals(0, writes) }
        }
}
