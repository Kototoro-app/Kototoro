package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.compose.*

@OptIn(ExperimentalTestApi::class)
class ContentCollectionsTest {
    @Test
    fun `grid and column request once near the end and resume after append or retry`() {
        for (grid in listOf(true, false)) runDesktopComposeUiTest(width = 380, height = 220) {
            var count by mutableStateOf(40)
            var canLoad by mutableStateOf(true)
            var requests = 0
            setContent {
                DesktopTheme {
                    if (grid) ContentCollectionGrid(count, { it.toLong() }, GridCells.Fixed(2),
                        modifier = Modifier.testTag("collection"), canLoadMore = canLoad,
                        onLoadMore = { requests++ }) { Text("Item $it", Modifier.height(60.dp)) }
                    else ContentCollectionColumn(count, { it.toLong() },
                        modifier = Modifier.testTag("collection"), canLoadMore = canLoad,
                        onLoadMore = { requests++ }) { Text("Item $it", Modifier.height(60.dp)) }
                }
            }
            val collection = onNodeWithTag("collection")
            runOnIdle { assertEquals(0, requests) }
            collection.performScrollToIndex(39)
            runOnIdle { assertEquals(1, requests) }
            collection.performScrollToIndex(0).performScrollToIndex(39)
            runOnIdle { assertEquals(1, requests); count = 50 }
            collection.performScrollToIndex(49)
            runOnIdle { assertEquals(2, requests); canLoad = false }
            collection.performScrollToIndex(0).performScrollToIndex(49)
            runOnIdle { assertEquals(2, requests); canLoad = true }
            runOnIdle { assertEquals(3, requests) }
        }
    }

    @Test
    fun `full width headers and supplementary rows keep card state attached to stable keys`() =
        runDesktopComposeUiTest(width = 380, height = 500) {
            var entries by mutableStateOf(listOf(1L, 2L, 3L))
            setContent {
                DesktopTheme {
                    ContentCollectionGrid(entries.size, { entries[it] }, GridCells.Fixed(2),
                        fullSpan = { entries[it] == 1L }, header = { Text("Header", Modifier.fillMaxWidth()) },
                        footer = { Text("Footer", Modifier.fillMaxWidth()) }) { index ->
                        val id = entries[index]
                        var expanded by remember { mutableStateOf(false) }
                        Text("$id:$expanded", Modifier.fillMaxWidth().height(60.dp).testTag("item:$id")
                            .clickable { expanded = !expanded })
                    }
                }
            }
            val header = onNodeWithText("Header").fetchSemanticsNode().boundsInRoot
            val supplementary = onNodeWithTag("item:1").fetchSemanticsNode().boundsInRoot
            assertEquals(header.width, supplementary.width)
            onNodeWithTag("item:2").performClick().assertTextEquals("2:true")
            runOnIdle { entries = listOf(1L, 3L, 2L) }
            onNodeWithTag("item:2").assertTextEquals("2:true")
            onNodeWithTag("item:3").assertTextEquals("3:false")
            onNodeWithText("Footer").assertIsDisplayed()
        }

    @Test
    fun `each shelf and display mode restores its own position after the collection leaves composition`() =
        runDesktopComposeUiTest(width = 380, height = 220) {
            var history by mutableStateOf(false)
            var grid by mutableStateOf(false)
            var details by mutableStateOf(false)
            lateinit var favourites: ContentCollectionState
            lateinit var historyState: ContentCollectionState
            setContent {
                favourites = rememberContentCollectionState()
                historyState = rememberContentCollectionState()
                DesktopTheme {
                    val state = if (history) historyState else favourites
                    if (!details) {
                        if (grid) ContentCollectionGrid(100, { it.toLong() }, GridCells.Fixed(2), state = state.grid,
                            modifier = Modifier.testTag("collection")) { Text("$it", Modifier.height(60.dp)) }
                        else ContentCollectionColumn(100, { it.toLong() }, state = state.list,
                            modifier = Modifier.testTag("collection")) { Text("$it", Modifier.height(60.dp)) }
                    }
                }
            }
            onNodeWithTag("collection").performScrollToIndex(40)
            runOnIdle { assertEquals(40, favourites.list.firstVisibleItemIndex); details = true }
            runOnIdle { details = false }
            runOnIdle { assertEquals(40, favourites.list.firstVisibleItemIndex); history = true }
            onNodeWithTag("collection").performScrollToIndex(10)
            runOnIdle { assertEquals(10, historyState.list.firstVisibleItemIndex); history = false; grid = true }
            onNodeWithTag("collection").performScrollToIndex(60)
            runOnIdle { assertEquals(60, favourites.grid.firstVisibleItemIndex); grid = false }
            runOnIdle { assertEquals(40, favourites.list.firstVisibleItemIndex) }
        }
}
