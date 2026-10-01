package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsCallbacks
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsPanel
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsState

/** Expands the real options panel with all quick actions present, without opening a book or changing settings. */
@RunWith(AndroidJUnit4::class)
class ReaderOptionsPanelSpaceTest {

    @Test
    fun expandedOptionsReserveMostOfTheWindowForSettings() = verifyOptionsSpace(eInk = true)

    @Test
    fun opaqueOptionsReserveSpaceAndRestoreQuickActions() = verifyOptionsSpace(eInk = false)

    private fun verifyOptionsSpace(eInk: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        ComposeReaderOptionsPanel(
                            state = ComposeReaderOptionsState(visible = true, mode = ReaderMode.WEBTOON),
                            callbacks = ComposeReaderOptionsCallbacks(),
                            translationAvailable = true,
                            translationActive = false,
                            eInkMode = eInk,
                        )
                    }
                }
            }
            val initialPeek = waitForStableBounds("Webtoon")
            repeat(2) { index ->
                val mode = waitForBounds(if (index == 0) "Webtoon" else "Layout")
                val endY = (mode.centerY() - 1400).coerceAtLeast(20)
                instrumentation.uiAutomation.executeShellCommand(
                    "input swipe ${mode.centerX()} ${mode.centerY()} ${mode.centerX()} $endY 400",
                ).use { it.close() }
                waitForStableBounds("Layout")
            }
            val layout = waitForStableBounds("Layout")
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val collapseStep = screenshot.height / 4
            try {
                if (InstrumentationRegistry.getArguments().getString("panelEvidence") == "true") {
                    File(context.cacheDir, "reader-options-expanded.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertTrue(
                    "Expanded settings start at ${layout.top}/${screenshot.height}; quick actions leave too little room",
                    layout.top < screenshot.height * 0.3f,
                )
            } finally {
                screenshot.recycle()
            }
            repeat(2) { index ->
                val tab = waitForBounds("Layout")
                instrumentation.uiAutomation.executeShellCommand(
                    "input swipe ${tab.centerX()} ${tab.centerY()} ${tab.centerX()} ${tab.centerY() + collapseStep} 400",
                ).use { it.close() }
                if (index == 0) waitForStableBounds("Layout")
            }
            val restoredPeek = waitForStableBounds("Webtoon")
            assertEquals("Collapsing settings must restore the original quick-action anchor", initialPeek, restoredPeek)
        }
    }

    private fun waitForBounds(text: String): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.let { root ->
                find(root, text)?.let { node ->
                    return Rect().also { node.getBoundsInScreen(it) }
                }
            }
            SystemClock.sleep(50)
        }
        error("Visible control not found: $text")
    }

    private fun waitForStableBounds(text: String): Rect {
        var previous = waitForBounds(text)
        var unchanged = 0
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            val current = waitForBounds(text)
            unchanged = if (current == previous) unchanged + 1 else 0
            if (unchanged >= 6) return current
            previous = current
        }
        error("Control did not settle: $text")
    }

    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, text)?.let { return it } }
        }
        return null
    }
}
