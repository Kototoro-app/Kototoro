package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.library.SourceLibraryCategory
import org.skepsun.kototoro.list.ui.compose.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ContentActionDialogsTest {
    @Test
    fun `confirmation keeps the warning visible and cancel or disabled confirm never commits`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var showing by mutableStateOf(true)
            var enabled by mutableStateOf(false)
            var commits = 0
            var cancels = 0
            setContent {
                DesktopTheme {
                    if (showing) ContentActionConfirmationDialog("Mark completed", "Current progress will be lost",
                        "Confirm", { commits++; showing = false }, { cancels++; showing = false },
                        selectionLabel = "3 selected", enabled = enabled)
                }
            }
            onNodeWithText("Current progress will be lost").assertIsDisplayed()
            onNodeWithText("3 selected").assertIsDisplayed()
            onNodeWithTag("content-confirm-confirm").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("content-confirm-dismiss").performClick()
            runOnIdle { assertEquals(0, commits); assertEquals(1, cancels); enabled = true; showing = true }
            onNodeWithTag("content-confirm-confirm").performClick()
            runOnIdle { assertEquals(1, commits); assertEquals(1, cancels) }
            onNodeWithTag("content-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `category choices preserve mixed states immediate toggles loading and hidden category recovery`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var state by mutableStateOf(ToggleableState.Indeterminate)
            var loading by mutableStateOf(false)
            var enabled by mutableStateOf(true)
            var empty by mutableStateOf(false)
            val toggles = mutableListOf<Boolean>()
            var showAll = 0
            setContent {
                DesktopTheme {
                    Surface {
                        CategoryChoicesContent(if (empty) emptyList() else listOf(
                            CategoryChoice(Long.MAX_VALUE, "Mixed category", state, tracking = true, hidden = true)),
                            onToggle = { id, checked ->
                                assertEquals(Long.MAX_VALUE, id)
                                toggles.add(checked); state = if (checked) ToggleableState.On else ToggleableState.Off
                            }, loading = loading, enabled = enabled, emptyMessage = "No categories",
                            hasHiddenCategories = true, onShowAll = { showAll++ })
                    }
                }
            }
            val category = onNodeWithTag("category-choices:${Long.MAX_VALUE}")
            category.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
            category.performClick().assertIsOn()
            category.performClick().assertIsOff()
            onNodeWithTag("category-choices-show-all").performClick()
            runOnIdle { assertEquals(listOf(true, false), toggles); assertEquals(1, showAll); enabled = false }
            category.assertIsNotEnabled().performTouchInput { click() }
            runOnIdle { assertEquals(2, toggles.size); loading = true }
            onNodeWithTag("category-choices-loading").assertIsDisplayed()
            category.assertDoesNotExist()
            runOnIdle { loading = false; empty = true; enabled = true }
            onNodeWithText("No categories").assertIsDisplayed()
            onNodeWithTag("category-choices-show-all").performClick()
            runOnIdle { assertEquals(2, showAll) }
        }

    @Test
    fun `desktop category drafts survive scrolling cancel without writes and save only on confirm`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 640) {
            val categories = (1L..50L).map { SourceLibraryCategory(it, "分类 $it") }
            var showing by mutableStateOf(true)
            var stored = setOf(1L)
            var commits = 0
            setContent {
                DesktopTheme {
                    if (showing) DesktopCategoryPickerDialog(3, categories, stored, true,
                        { stored = it; commits++; showing = false }, { showing = false })
                }
            }
            onNodeWithTag("library-category-pick:1").performClick()
            onNode(hasScrollToIndexAction()).performScrollToIndex(49)
            onNodeWithTag("library-category-pick:50").performClick().assertIsOn()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-category-draft-$width.png")
            Image.makeFromBitmap(onNodeWithTag("library-category-pick-dialog").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("library-selection-dismiss").performClick()
            runOnIdle { assertEquals(setOf(1L), stored); assertEquals(0, commits); showing = true }
            onNodeWithTag("library-category-pick:1").assertIsOn()
            onNode(hasScrollToIndexAction()).performScrollToIndex(49)
            onNodeWithTag("library-category-pick:50").performClick()
            onNodeWithTag("library-category-pick-confirm").performClick()
            runOnIdle { assertEquals(setOf(1L, 50L), stored); assertEquals(1, commits) }
        }
    }
}
