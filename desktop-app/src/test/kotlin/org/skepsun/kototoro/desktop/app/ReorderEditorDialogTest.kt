package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.ReorderEditorDialog
import org.skepsun.kototoro.core.ui.compose.ReorderItem
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ReorderEditorDialogTest {
    private val items = listOf(ReorderItem("a1", "First", "A"), ReorderItem("a2", "Second", "A"),
        ReorderItem("b", "Other group", "B"))

    @Test
    fun groupedMovesStayDraftUntilSaveAndCancelDiscardsThem() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var visible by mutableStateOf(true)
            var saved: List<String>? = null
            setContent {
                DesktopTheme {
                    if (visible) ReorderEditorDialog(items, { visible = false }, { saved = it },
                        showMoveButtons = true, tagPrefix = "draft")
                }
            }
            onNodeWithTag("draft-up:a1").assertIsNotEnabled()
            onNodeWithTag("draft-down:a2").assertIsNotEnabled()
            onNodeWithTag("draft-up:b").assertIsNotEnabled()
            onNodeWithTag("draft-up:a2").performClick()
            runOnIdle { assertNull(saved) }
            onNodeWithTag("draft-cancel").performClick()
            runOnIdle { assertNull(saved); visible = true }
            val first = onNodeWithTag("draft:a1").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("draft:a2").fetchSemanticsNode().boundsInRoot
            assertTrue(first.top < second.top)
            onNodeWithTag("draft-up:a2").performClick()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-reorder-$width.png")
            Image.makeFromBitmap(onNodeWithTag("draft-dialog").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("draft-save").performClick()
            waitUntil { saved != null && !visible }
            runOnIdle { assertEquals(listOf("a2", "a1", "b"), saved) }
        }
    }

    @Test
    fun failedSaveCanRetryAndPendingSaveBlocksEveryMoveAndCancel() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            val release = CompletableDeferred<Unit>()
            var visible by mutableStateOf(true)
            var attempts = 0
            var saved: List<String>? = null
            var dismissals = 0
            setContent {
                DesktopTheme {
                    if (visible) ReorderEditorDialog(items, { dismissals++; visible = false }, {
                        attempts++
                        if (attempts == 1) error("Cannot save")
                        release.await()
                        saved = it
                    }, showMoveButtons = true, tagPrefix = "draft")
                }
            }
            onNodeWithTag("draft-save").performClick()
            waitUntil { onAllNodesWithText("Cannot save").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("draft-save").performClick()
            waitUntil { attempts == 2 }
            onNodeWithTag("draft-save").assertIsNotEnabled()
            onNodeWithTag("draft-cancel").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("draft-up:a2").assertIsNotEnabled()
            val actions = onNodeWithTag("draft:a2").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            runOnIdle {
                assertFalse(actions.first().action())
                assertEquals(0, dismissals)
                release.complete(Unit)
            }
            waitUntil { !visible }
            runOnIdle { assertEquals(listOf("a1", "a2", "b"), saved); assertEquals(1, dismissals) }
        }

    @Test
    fun touchDragUsesTheSameExplicitSaveSession() = runDesktopComposeUiTest(width = 380, height = 800) {
        var saved: List<String>? = null
        setContent { DesktopTheme { ReorderEditorDialog(items, {}, { saved = it }, tagPrefix = "draft") } }
        val height = onNodeWithTag("draft:a1").fetchSemanticsNode().boundsInRoot.height
        onNodeWithTag("draft-handle:a1").performTouchInput {
            swipe(center, center + Offset(0f, height + 8f), durationMillis = 800)
        }
        runOnIdle { assertNull(saved) }
        onNodeWithTag("draft-save").performClick()
        waitUntil { saved != null }
        runOnIdle { assertEquals(listOf("a2", "a1", "b"), saved) }
    }

    @Test
    fun disabledAndEmptySessionsCannotSaveButCanBeDismissed() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var cancelled = 0
            setContent {
                DesktopTheme { ReorderEditorDialog(emptyList(), { cancelled++ }, { error("nothing to save") },
                    enabled = false, emptyText = "No sources", tagPrefix = "draft") }
            }
            onNodeWithTag("draft-empty").assertExists()
            onNodeWithTag("draft-save").assertIsNotEnabled()
            onNodeWithTag("draft-cancel").performClick()
            runOnIdle { assertEquals(1, cancelled) }
        }
    @Test
    fun livePriorityMovesWriteOnceAndDoNotCloseTheEditor() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            var visible by mutableStateOf(true)
            var current by mutableStateOf(items)
            val writes = mutableListOf<List<String>>()
            setContent {
                DesktopTheme {
                    if (visible) ReorderEditorDialog(current, { visible = false }, { keys ->
                        writes += keys
                        current = keys.map { key -> items.first { it.key == key } }
                    }, saveOnMove = true, tagPrefix = "live")
                }
            }
            onNodeWithTag("live-save").assertDoesNotExist()
            onNodeWithTag("live-up:a2").performClick()
            waitUntil { writes.size == 1 }
            onNodeWithTag("live-dialog").assertExists()
            onNodeWithTag("live-up:b").assertIsNotEnabled()
            runOnIdle { assertEquals(listOf(listOf("a2", "a1", "b")), writes) }
            onNodeWithTag("live-close").performClick()
            runOnIdle { assertFalse(visible); assertEquals(1, writes.size) }
        }

    @Test
    fun AndroidPreferenceKeepsRawJarKeysAndCommitsOnlyOnConfirmation() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            var current by mutableStateOf(listOf("first.jar", "second.jar"))
            var writes = 0
            setContent {
                DesktopTheme {
                    org.skepsun.kototoro.settings.compose.SettingsReorderPreference(
                        title = "Priority", value = current, emptyValueText = "None",
                        onValueChange = { current = it; writes++ })
                }
            }
            onNodeWithText("Priority").performClick()
            onNodeWithTag("reorder-up:second.jar").performClick()
            runOnIdle { assertEquals(0, writes) }
            onNodeWithTag("reorder-cancel").performClick()
            onNodeWithText("Priority").performClick()
            onNodeWithTag("reorder-up:second.jar").performClick()
            onNodeWithTag("reorder-save").performClick()
            waitUntil { writes == 1 }
            runOnIdle { assertEquals(listOf("second.jar", "first.jar"), current) }
        }

    @Test
    fun AndroidPreferenceCanConfirmAnEmptyPriorityAsBefore() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var writes = 0
            setContent {
                DesktopTheme {
                    org.skepsun.kototoro.settings.compose.SettingsReorderPreference(
                        title = "Priority", value = emptyList(), emptyValueText = "None",
                        onValueChange = { assertTrue(it.isEmpty()); writes++ })
                }
            }
            onNodeWithText("Priority").performClick()
            onNodeWithTag("reorder-save").assertIsEnabled().performClick()
            waitUntil { writes == 1 }
        }

    @Test
    fun anotherAccessibilityMoveCannotEnterBeforeThePendingSaveRecomposes() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            val release = CompletableDeferred<Unit>()
            var writes = 0
            setContent {
                DesktopTheme {
                    ReorderEditorDialog(items, {}, { release.await(); writes++ }, saveOnMove = true, tagPrefix = "live")
                }
            }
            val actions = onNodeWithTag("live:a2").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            runOnIdle {
                assertTrue(actions[0].action())
                assertFalse(actions[1].action())
                assertEquals(0, writes)
                release.complete(Unit)
            }
            waitUntil { writes == 1 }
        }

}
