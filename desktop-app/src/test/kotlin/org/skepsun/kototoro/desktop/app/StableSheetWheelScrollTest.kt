package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.StableAnchoredSheetLayout

/**
 * A mouse wheel or a trackpad's two-finger scroll arrives as `PointerEventType.Scroll`: nested scroll input
 * that comes with no press. The anchored sheet must not answer it with a drag — on Windows the reader's
 * chapter panel used to slide away and dismiss on the first wheel tick, so the list inside it could never be
 * scrolled (issue #601). A pressed drag keeps Android's collapse.
 */
class StableSheetWheelScrollTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wheel scrolling over the sheet list scrolls it instead of dismissing the sheet`() {
        runDesktopComposeUiTest(width = 480, height = 640) {
            var dismissed = 0
            lateinit var list: LazyListState
            setContent {
                DesktopTheme {
                    list = rememberLazyListState()
                    TestSheet(dismissed = { dismissed++ }, state = list)
                }
            }
            waitForIdle()
            onNodeWithTag("sheet-list").assertExists()
            onNodeWithTag("sheet-list").performMouseInput { moveTo(center); scroll(3f) }
            waitForIdle()
            assertEquals(0, dismissed, "a wheel scroll must not dismiss the sheet")
            assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0,
                "the wheel scroll belongs to the list inside the sheet")
            onNodeWithTag("sheet-list").performMouseInput { moveTo(center); scroll(-3f) }
            waitForIdle()
            assertEquals(0, dismissed, "scrolling back up at the top must not dismiss the sheet either")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging the sheet header with a pressed pointer still moves the sheet`() {
        runDesktopComposeUiTest(width = 480, height = 640) {
            var dismissed = 0
            lateinit var list: LazyListState
            setContent {
                DesktopTheme {
                    list = rememberLazyListState()
                    TestSheet(dismissed = { dismissed++ }, state = list)
                }
            }
            waitForIdle()
            val before = onNodeWithTag("sheet-list").fetchSemanticsNode().boundsInRoot.top
            // Downwards, past the three-quarter anchor: Android's drag still collapses the sheet.
            onNodeWithTag("sheet-header").performMouseInput {
                moveTo(center); press()
                repeat(20) { advanceEventTime(16); moveBy(Offset(0f, 24f)) }
                release()
            }
            waitForIdle()
            val after = onNodeWithTag("sheet-list").fetchSemanticsNode().boundsInRoot.top
            assertTrue(after > before, "a pressed drag must still pull the sheet down")
            assertEquals(0, list.firstVisibleItemIndex, "the dragged list must not scroll while the sheet follows")
            assertEquals(0, list.firstVisibleItemScrollOffset, "the dragged list must not scroll while the sheet follows")
        }
    }
}

/** The shared anchored sheet with one scrollable list, as the reader and novel panels host it. */
@Composable
private fun TestSheet(dismissed: () -> Unit, state: LazyListState) {
    StableAnchoredSheetLayout(
        onDismissRequest = dismissed,
        openExpanded = true,
        modifier = Modifier.fillMaxSize().testTag("sheet"),
    ) { scope ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(56.dp).then(scope.dragModifier).testTag("sheet-header"))
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f).testTag("sheet-list")) {
                items((1..40).toList()) { row ->
                    Text("行 $row", modifier = Modifier.fillMaxWidth().height(40.dp).testTag("sheet-row:$row"))
                }
            }
        }
    }
}
