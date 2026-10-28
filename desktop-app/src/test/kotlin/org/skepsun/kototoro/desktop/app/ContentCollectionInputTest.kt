package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.compose.ContentCollectionColumn
import org.skepsun.kototoro.list.ui.compose.ContentCollectionGrid
import org.skepsun.kototoro.list.ui.compose.ContentCollectionState
import org.skepsun.kototoro.list.ui.compose.rememberContentCollectionState

/** Exercises the same grid/column that favourites, history and browse shelves actually render. */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class ContentCollectionInputTest {
    @Test fun `favourites grid scrolls when dragging a clickable card with the mouse`() = checkInput(grid = true, mouse = true)
    @Test fun `favourites list scrolls when dragging a clickable row with the mouse`() = checkInput(grid = false, mouse = true)
    @Test fun `favourites grid retains native touch scrolling`() = checkInput(grid = true, mouse = false)
    @Test fun `favourites list retains native touch scrolling`() = checkInput(grid = false, mouse = false)

    private fun checkInput(grid: Boolean, mouse: Boolean) = runDesktopComposeUiTest(width = 400, height = 300) {
        lateinit var state: ContentCollectionState
        var clicks = 0
        setContent {
            state = rememberContentCollectionState()
            DesktopTheme {
                if (grid) {
                    ContentCollectionGrid(100, { it }, GridCells.Fixed(2), state = state.grid,
                        modifier = Modifier.testTag("collection")) { index ->
                        Text("Work $index", Modifier.fillMaxWidth().height(100.dp).testTag("item:$index")
                            .combinedClickable(onClick = { clicks++ }, onLongClick = {}))
                    }
                } else {
                    ContentCollectionColumn(100, { it }, state = state.list,
                        modifier = Modifier.testTag("collection")) { index ->
                        Text("Work $index", Modifier.fillMaxWidth().height(100.dp).testTag("item:$index")
                            .combinedClickable(onClick = { clicks++ }, onLongClick = {}))
                    }
                }
            }
        }
        fun scrolled() = if (grid) state.grid.run { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }
            else state.list.run { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }

        val collection = onNodeWithTag("collection")
        if (mouse) collection.performMouseInput {
            moveTo(Offset(width * .25f, height * .85f))
            press()
            repeat(7) { advanceEventTime(16); moveBy(Offset(0f, -24f)) }
            release()
        } else collection.performTouchInput { swipeUp() }
        waitForIdle()
        runOnIdle {
            assertTrue(scrolled(), "${if (grid) "Grid" else "List"} must scroll with ${if (mouse) "mouse" else "touch"}")
            assertEquals(0, clicks, "Dragging must not open or select a work")
            runBlocking { if (grid) state.grid.scrollToItem(0) else state.list.scrollToItem(0) }
        }
        if (mouse) {
            onNodeWithTag("item:0").performMouseInput {
                moveTo(center); press(); moveBy(Offset(2f, 1f)); release()
            }
            runOnIdle { assertEquals(1, clicks, "Motion below slop must remain a normal click") }
            collection.performMouseInput {
                moveTo(center); press(MouseButton.Secondary)
                repeat(4) { advanceEventTime(16); moveBy(Offset(0f, -24f)) }
                release(MouseButton.Secondary)
            }
            runOnIdle { assertTrue(!scrolled(), "A secondary-button drag must not grab-scroll") }
            collection.performMouseInput { moveTo(center); scroll(3f) }
            waitForIdle()
            runOnIdle { assertTrue(scrolled(), "Mouse wheel scrolling must still work") }
        } else {
            onNodeWithTag("item:0").performTouchInput { click() }
            runOnIdle { assertEquals(1, clicks) }
        }
    }
}
