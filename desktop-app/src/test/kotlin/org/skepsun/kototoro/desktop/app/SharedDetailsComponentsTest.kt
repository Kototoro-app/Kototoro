package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataEntry
import org.skepsun.kototoro.core.ui.compose.DetailsMetadataGrid
import org.skepsun.kototoro.core.ui.compose.ExpandableDescriptionText
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

@OptIn(ExperimentalTestApi::class)
class SharedDetailsComponentsTest {
    @Test
    fun `a long description toggles on click and a short one stays plain text`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var expanded by mutableStateOf(false)
            setContent {
                DesktopTheme {
                    Surface {
                        Column(Modifier.width(300.dp)) {
                            ExpandableDescriptionText("Line. ".repeat(200), expanded, { expanded = it },
                                modifier = Modifier.testTag("long"))
                            ExpandableDescriptionText("Short.", false, { error("short text must not toggle") },
                                modifier = Modifier.testTag("short"))
                        }
                    }
                }
            }
            val collapsed = onNodeWithTag("long").fetchSemanticsNode().size.height
            onNodeWithTag("short").assertHasNoClickAction()
            onNodeWithTag("long").assertHasClickAction().performClick()
            runOnIdle { assertTrue(expanded) }
            waitForIdle()
            assertTrue(onNodeWithTag("long").fetchSemanticsNode().size.height > collapsed * 2) { "unfolds the text" }
            onNodeWithTag("long").performClick()
            runOnIdle { assertFalse(expanded) }
        }

    @Test
    fun `metadata facts name their field for screen readers`() =
        runDesktopComposeUiTest(width = 380, height = 300) {
            setContent {
                DesktopTheme {
                    Surface {
                        DetailsMetadataGrid(listOf(
                            DetailsMetadataEntry("State", "Ongoing", rememberVectorPainter(ReaderPanelIcons.Timer)),
                            DetailsMetadataEntry("Rating", "5.0", null),
                            DetailsMetadataEntry("Alias", "Other", rememberVectorPainter(ReaderPanelIcons.Search)),
                        ))
                    }
                }
            }
            onNodeWithContentDescription("State").assertExists()
            onNodeWithContentDescription("Rating").assertExists()
            onNodeWithText("Ongoing").assertExists()
            onNodeWithText("Other").assertExists()
        }
}
