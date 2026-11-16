package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.LocalSettingsSearchTarget
import org.skepsun.kototoro.core.ui.settings.SettingsSearchLazyReveal
import org.skepsun.kototoro.core.ui.settings.SettingsSearchTarget
import org.skepsun.kototoro.settings.compose.SettingsActionPreference
import org.skepsun.kototoro.settings.compose.SettingsPreferenceGroup

/** A search result that lands on a lazy settings page must end up on its row, not at the page top. */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchLazyRevealTest {

    @Test
    fun `a lazy page walks to the row whose title was searched and stays at the top for a foreign one`() =
        runDesktopComposeUiTest(width = 390, height = 640) {
            var target by mutableStateOf<SettingsSearchTarget?>(null)
            val listState = LazyListState()
            setContent {
                DesktopTheme {
                    CompositionLocalProvider(LocalSettingsSearchTarget provides target) {
                        SettingsSearchLazyReveal(listState)
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(12) { group ->
                                SettingsPreferenceGroup(title = "Group $group") {
                                    repeat(4) { row ->
                                        item { SettingsActionPreference(title = "Row $group-$row", onClick = {}) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            onNodeWithText("Row 9-2").assertDoesNotExist()
            runOnIdle { target = SettingsSearchTarget("no_key_on_this_row", 1, "Row 9-2") }
            onNodeWithText("Row 9-2").assertIsDisplayed()
            runOnIdle { assertTrue(listState.firstVisibleItemIndex > 0) }

            runOnIdle { target = SettingsSearchTarget("elsewhere", 2, "Not on this page") }
            runOnIdle {
                assertEquals(0, listState.firstVisibleItemIndex)
                assertEquals(0, listState.firstVisibleItemScrollOffset)
            }
        }
}
