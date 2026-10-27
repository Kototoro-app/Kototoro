package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.BlacklistTagUiModel
import org.skepsun.kototoro.core.ui.settings.BlacklistedTagsContent

/**
 * The shared blacklist grid both hosts draw: the catalogue grouped by category with the aliases under each label, the
 * selected section that repeats a chip, the long-press details callback, and the host slots that keep a page's own rows
 * inside the same scroll container.
 */
@OptIn(ExperimentalTestApi::class)
class BlacklistedTagsContentTest {
    private fun tag(
        key: String,
        label: String,
        aliases: List<String> = emptyList(),
        categoryId: String = "genre",
        categoryName: String = "Genre",
    ) = BlacklistTagUiModel(key, label, aliases, categoryId, categoryName)

    private val drama = tag("genre.drama", "Drama", listOf("dramatic", "dramas"))
    private val catalogue = listOf(
        drama,
        tag("genre.comedy", "Comedy"),
        tag("theme.school", "School", categoryId = "theme", categoryName = "Theme"),
    )

    @Test
    fun catalogueGroupsByCategoryAndReportsTogglesAndLongPresses() {
        for (width in listOf(420, 900)) runDesktopComposeUiTest(width = width, height = 900) {
            var toggled = emptyList<String>()
            var details: BlacklistTagUiModel? = null
            var rawAdded = 0
            var query by mutableStateOf("")
            setContent {
                DesktopTheme {
                    Surface {
                        BlacklistedTagsContent(
                            available = catalogue,
                            selected = listOf(drama),
                            query = query,
                            onQueryChange = { query = it },
                            onToggle = { toggled = toggled + it },
                            onShowDetails = { details = it },
                            rawPatternLabel = "Add “Foo”",
                            onAddRawPattern = { rawAdded++ },
                            searchModifier = Modifier.testTag("bl-search"),
                            tagPrefix = "bl",
                            header = { Text("Host header") },
                            footer = { Text("Host footer") },
                        )
                    }
                }
            }
            onNodeWithText("Host header").assertExists()
            onNodeWithText("Host footer").assertExists()
            onNodeWithTag("bl-available").assertExists()
            onNodeWithTag("bl-selected-count").assertExists()
            // The catalogue keeps the taxonomy order, so Genre comes before Theme.
            onNodeWithText("Genre").assertExists()
            onNodeWithText("Theme").assertExists()
            // A chip shows the aliases Android shows on long press, and a click toggles the stored key.
            // The aliases line is drawn on both the selected copy and the catalogue copy.
            onAllNodesWithText("dramatic · dramas").assertCountEquals(2)
            onNodeWithTag("bl:genre.comedy").performClick()
            waitForIdle()
            assertEquals(listOf("genre.comedy"), toggled)
            // The selected section repeats the chip, marked selected, with its own tag for the host's probes.
            onNodeWithTag("bl-selected:genre.drama").assertExists()
            onNodeWithTag("bl-selected:genre.drama").performTouchInput { longClick() }
            waitForIdle()
            assertEquals("genre.drama", details?.key)
            onNodeWithTag("bl-selected:genre.drama").assert(isSelected())
            // The host's own "add this pattern" action sits in the shared grid and keeps its key.
            onNodeWithTag("bl-add").performClick()
            waitForIdle()
            assertEquals(1, rawAdded)
            onNodeWithTag("bl-search").performTextInput("Dra")
            waitForIdle()
            assertEquals("Dra", query)
        }
    }

    @Test
    fun emptyCatalogueShowsTheSharedEmptyStateWithoutTheRawAction() {
        runDesktopComposeUiTest(width = 600, height = 800) {
            var details: BlacklistTagUiModel? = null
            setContent {
                DesktopTheme {
                    Surface {
                        Column {
                            BlacklistedTagsContent(
                                available = emptyList(),
                                selected = emptyList(),
                                query = "",
                                onQueryChange = {},
                                onToggle = {},
                                onShowDetails = { details = it },
                                tagPrefix = "bl",
                            )
                        }
                    }
                }
            }
            onNodeWithTag("bl-empty-text").assertExists()
            onNodeWithTag("bl-add").assertDoesNotExist()
            onNodeWithTag("bl-selected-count").assertExists()
            assertNull(details)
        }
    }
}
