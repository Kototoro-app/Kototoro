package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.compose.*
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ContentSelectionToolbarTest {
    @Test
    fun `all actions remain reachable without overflow in narrow and wide light and dark toolbars`() {
        for (width in listOf(280, 380, 900)) for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = width, height = 500) {
                var count by mutableStateOf(2)
                val actions = mutableListOf<SelectionAction>()
                var cancelled = false
                val offered = listOf(SelectionAction.SELECT_ALL, SelectionAction.PIN, SelectionAction.REMOVE,
                    SelectionAction.SAVE, SelectionAction.FAVOURITE, SelectionAction.SHARE, SelectionAction.MIGRATE)
                setContent {
                    DesktopTheme(appearance) {
                        androidx.compose.material3.Surface {
                            ContentSelectionToolbar("$count selected", offered.map {
                                SelectionToolbarItem(it, it.name, rememberVectorPainter(ReaderPanelIcons.Check),
                                    enabled = it != SelectionAction.MIGRATE || count == 1)
                            }, listOf(SelectionAction.PIN, SelectionAction.REMOVE, SelectionAction.SAVE),
                                { cancelled = true }, { actions.add(it) })
                        }
                    }
                }
                onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
                    assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width + 1)
                }
                val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                    .resolve("selection-toolbar-$width-${appearance.name}.png")
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(report, it.bytes) }
                }
                fun openAction(action: SelectionAction): SemanticsNodeInteraction {
                    val tag = "selection-action:${action.name}"
                    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) onNodeWithTag("selection-more").performClick()
                    return onNodeWithTag(tag)
                }
                openAction(SelectionAction.MIGRATE).assertIsNotEnabled()
                openAction(SelectionAction.SHARE).performClick()
                onNodeWithText("MIGRATE").assertDoesNotExist()
                runOnIdle { assertEquals(listOf(SelectionAction.SHARE), actions); count = 1 }
                openAction(SelectionAction.MIGRATE).assertIsEnabled().performClick()
                for (action in offered - SelectionAction.SHARE - SelectionAction.MIGRATE) openAction(action).performClick()
                runOnIdle { assertEquals(offered.toSet(), actions.toSet()); assertEquals(offered.size, actions.size) }
                onNodeWithTag("selection-cancel").performClick()
                runOnIdle { assertTrue(cancelled) }
            }
        }
    }

    @Test
    fun `busy state disables operations while cancel remains available and unsupported actions stay absent`() =
        runDesktopComposeUiTest(width = 380, height = 400) {
            var cancelled = false
            var actions = 0
            setContent {
                DesktopTheme {
                    ContentSelectionToolbar("2", listOf(SelectionToolbarItem(SelectionAction.REMOVE, "Remove"),
                        SelectionToolbarItem(SelectionAction.SELECT_ALL, "All")), listOf(SelectionAction.REMOVE),
                        { cancelled = true }, { actions++ }, enabled = false)
                }
            }
            onNodeWithTag("selection-action:REMOVE").assertIsNotEnabled()
            onNodeWithTag("selection-cancel").assertIsEnabled().performClick()
            runOnIdle { assertTrue(cancelled); assertEquals(0, actions) }
            onNodeWithTag("selection-more").performClick()
            onNodeWithTag("selection-action:SELECT_ALL").assertIsNotEnabled()
                .performTouchInput { click() }
            onNodeWithTag("selection-action:SHARE").assertDoesNotExist()
            runOnIdle { assertEquals(0, actions) }
        }
}
