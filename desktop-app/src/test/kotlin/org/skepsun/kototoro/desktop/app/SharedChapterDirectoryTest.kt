package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.chapters.*
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

class SharedChapterDirectoryTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `episode thumbnails sit before list titles and above grid titles at narrow and wide widths`() {
        for (width in listOf(340, 960)) for (grid in listOf(false, true)) {
            runDesktopComposeUiTest(width = width, height = 240) {
                var clicks = 0
                setContent {
                    DesktopTheme(DesktopAppearance.LIGHT) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.padding(12.dp).width(if (grid) 180.dp else 316.dp)) {
                                val data = ChapterCardData("Episode 12", "A familiar scene", isBookmarked = true)
                                val image: @Composable BoxScope.() -> Unit = {
                                    Box(Modifier.fillMaxSize().background(Color(0xFF385D86)).testTag("thumbnail"))
                                }
                                if (grid) ChapterGridCard(data, false, { clicks++ },
                                    modifier = Modifier.testTag("episode"), thumbnail = image)
                                else ChapterListCard(data, false, { clicks++ },
                                    modifier = Modifier.testTag("episode"), thumbnail = image)
                            }
                        }
                    }
                }
                val thumbnail = onNodeWithTag("thumbnail", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val title = onNodeWithText("Episode 12", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(16f / 9f, thumbnail.width / thumbnail.height, .03f)
                if (grid) assertTrue(title.top >= thumbnail.bottom) else assertTrue(title.left >= thumbnail.right)
                onNodeWithTag("episode").performClick()
                assertEquals(1, clicks)
                onNodeWithContentDescription("Bookmarked", useUnmergedTree = true).assertExists()
                val path = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                    .resolve("episode-thumbnail-$width-${if (grid) "grid" else "list"}.png")
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(path, it.bytes) }
                }
            }
        }
    }

    private data class Entry(val number: Int, val header: Boolean = false)
    private val entries = (0..119).flatMap { number ->
        if (number % 10 == 0) listOf(Entry(number, true), Entry(number)) else listOf(Entry(number))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `current chapter locates in list and grid with full width headers and duplicate source ids`() {
        for (width in listOf(340, 960)) runDesktopComposeUiTest(width = width, height = 420) {
            var grid by mutableStateOf(false)
            var locate by mutableIntStateOf(0)
            lateinit var list: LazyListState
            lateinit var gridState: LazyGridState
            val selected = mutableListOf<Int>()
            setContent {
                DesktopTheme {
                    list = rememberLazyListState()
                    gridState = rememberLazyGridState()
                    ChapterDirectoryLayout(entries, { if (it.header) "header" else "duplicate-id" }, { it.header },
                        grid, 1f, initialItemIndex = entries.indexOf(Entry(84)), locateRequest = locate,
                        listState = list, gridState = gridState) { _, entry, isGrid ->
                        if (entry.header) ChapterSectionHeader("Volume ${entry.number / 10}",
                            Modifier.testTag("header:${entry.number}"))
                        else {
                            val data = ChapterCardData("Chapter ${entry.number}", isCurrent = entry.number == 84)
                            val modifier = Modifier.testTag("card:${entry.number}")
                            if (isGrid) ChapterGridCard(data, false, { selected += entry.number }, modifier = modifier)
                            else ChapterListCard(data, false, { selected += entry.number }, modifier = modifier)
                        }
                    }
                }
            }
            onNodeWithTag("card:84").assertIsDisplayed()
            onNodeWithTag("chapter-directory-list").performScrollToIndex(0)
            onNodeWithTag("card:84").assertDoesNotExist()
            runOnIdle { locate++ }
            onNodeWithTag("card:84").assertIsDisplayed()
            runOnIdle { grid = true }
            onNodeWithTag("card:84").assertIsDisplayed()
            onNodeWithTag("card:84").performClick()
            assertEquals(listOf(84), selected)
            onNodeWithTag("chapter-directory-grid").performScrollToIndex(0)
            val header = onNodeWithTag("header:0").fetchSemanticsNode().boundsInRoot
            val first = onNodeWithTag("card:0").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("card:1").fetchSemanticsNode().boundsInRoot
            assertEquals(first.width, first.height, .1f)
            assertTrue(header.width > first.width * 2, "A section header must span the entire grid")
            assertEquals(first.top, second.top, .1f)
            assertEquals(4f, second.left - first.right, .5f)
            runOnIdle { locate++ }
            onNodeWithTag("card:84").assertIsDisplayed()
            runOnIdle { grid = false }
            onNodeWithTag("card:84").assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `directory mouse wheel and touch move both layouts without selecting and respect disabled scrolling`() {
        for (grid in listOf(false, true)) runDesktopComposeUiTest(width = 340, height = 300) {
            lateinit var list: LazyListState
            lateinit var gridState: LazyGridState
            var enabled by mutableStateOf(true)
            var clicks = 0
            setContent {
                DesktopTheme {
                    list = rememberLazyListState()
                    gridState = rememberLazyGridState()
                    ChapterDirectoryLayout((0..199).toList(), { it.toString() }, { false }, grid, 1f,
                        isScrollEnabled = enabled, listState = list, gridState = gridState) { _, number, isGrid ->
                        val data = ChapterCardData("Chapter $number")
                        if (isGrid) ChapterGridCard(data, false, { clicks++ }, enabled = enabled)
                        else ChapterListCard(data, false, { clicks++ }, enabled = enabled)
                    }
                }
            }
            val tag = if (grid) "chapter-directory-grid" else "chapter-directory-list"
            fun position(): Float = onNodeWithTag(tag).fetchSemanticsNode().config[
                SemanticsProperties.VerticalScrollAxisRange].value()
            onNodeWithTag(tag).performMouseInput {
                moveTo(center); press()
                repeat(7) { advanceEventTime(16); moveBy(Offset(0f, -18f)) }
                release()
            }
            waitForIdle()
            assertTrue(position() > 0)
            assertEquals(0, clicks, "Grabbing a chapter card must scroll, not open it")
            val dragged = position()
            onNodeWithTag(tag).performMouseInput { moveTo(center); scroll(4f) }
            waitForIdle()
            assertTrue(position() > dragged)
            onNodeWithTag(tag).performMouseInput { exit() }
            val wheeled = position()
            onNodeWithTag(tag).performTouchInput { swipeUp() }
            waitForIdle()
            assertTrue(position() > wheeled)
            runOnIdle { enabled = false }
            val disabled = position()
            onNodeWithTag(tag).performMouseInput {
                moveTo(center); press(); moveBy(Offset(0f, -100f)); release(); scroll(4f)
            }
            waitForIdle()
            assertEquals(disabled, position(), .001f)
            assertEquals(0, clicks)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `chapter cards keep Android status markers long clicks and disabled actions in both themes`() {
        for (grid in listOf(false, true)) for (theme in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = 220, height = 220) {
                var clicks = 0
                var longClicks = 0
                var enabled by mutableStateOf(true)
                val data = ChapterCardData("Chapter 10", "#10 • Translator", isCurrent = true, isUnread = false,
                    isDownloaded = true, isBookmarked = true, isNew = true)
                setContent {
                    DesktopTheme(theme) {
                        if (grid) ChapterGridCard(data, true, { clicks++ }, { longClicks++ },
                            enabled = enabled, modifier = Modifier.testTag("card"))
                        else ChapterListCard(data, true, { clicks++ }, { longClicks++ },
                            enabled = enabled, modifier = Modifier.testTag("card"))
                    }
                }
                onNodeWithContentDescription("Bookmarked", useUnmergedTree = true).assertExists()
                onNodeWithContentDescription("Downloaded", useUnmergedTree = true).assertExists()
                if (grid) {
                    onNodeWithContentDescription("Current", useUnmergedTree = true).assertExists()
                    onNodeWithContentDescription("New", useUnmergedTree = true).assertExists()
                } else onNodeWithText("#10 • Translator", useUnmergedTree = true).assertExists()
                onNodeWithTag("card").performTouchInput { longClick() }
                assertEquals(1, longClicks)
                assertEquals(0, clicks)
                onNodeWithTag("card").performClick()
                assertEquals(1, clicks)
                runOnIdle { enabled = false }
                onNodeWithTag("card").assertIsNotEnabled().performMouseInput { click() }
                assertEquals(1, clicks)
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `refreshed collapsed empty and loading lists keep lazy state valid`() {
        runDesktopComposeUiTest(width = 340, height = 400) {
            var items by mutableStateOf(entries)
            var loading by mutableStateOf(false)
            var grid by mutableStateOf(false)
            var current by mutableIntStateOf(items.indexOf(Entry(84)))
            setContent {
                DesktopTheme {
                    ChapterDirectoryLayout(items, { "${it.header}:${it.number}" }, { it.header }, grid, 1f,
                        initialItemIndex = current, isLoading = loading, emptyMessage = "No chapters") { _, item, _ ->
                        Text("${item.number}", modifier = Modifier.fillMaxWidth().height(56.dp).testTag("item:${item.number}"))
                    }
                }
            }
            onNodeWithTag("item:84").assertIsDisplayed()
            runOnIdle { items = listOf(Entry(84)); current = 0; grid = true }
            onNodeWithTag("item:84").assertIsDisplayed()
            runOnIdle { items = emptyList(); current = 999 }
            onNodeWithText("No chapters").assertIsDisplayed()
            runOnIdle { loading = true }
            onNodeWithText("No chapters").assertDoesNotExist()
            onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
            runOnIdle { loading = false; items = listOf(Entry(1)); grid = false }
            onNodeWithTag("item:1").assertIsDisplayed()
        }
    }
}
