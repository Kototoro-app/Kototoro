package org.skepsun.kototoro.migration.ui.health

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.ui.text.AndroidUiStrings
import org.skepsun.kototoro.core.ui.text.LocalUiStrings

/**
 * The broken-sources card used to draw its message as one character per line at 1.5×, because a weighted message
 * shared a row with three actions wider than the row itself. These checks keep the message readable against its real
 * accessibility bounds, so a layout that collapses it fails here instead of on the library screen.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SourceHealthBannerLayoutTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun defaultFontKeepsTheMessageOnOneLine() = verifyBanner(fontScale = 1f)

    @Test
    fun largeFontKeepsTheMessageReadable() = verifyBanner(fontScale = 1.5f)

    @Test
    fun largestFontKeepsTheMessageReadable() = verifyBanner(fontScale = 2f)

    private fun verifyBanner(fontScale: Float) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val widthDp = 411
        val actions = CopyOnWriteArrayList<String>()
        val command = "am start -W --windowingMode 1 -n " +
            "${context.packageName}/${IdleProbeActivity::class.java.name} --ez scene_recovery true"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
        var activity: IdleProbeActivity? = null
        val launchDeadline = SystemClock.uptimeMillis() + 5_000
        while (activity == null && SystemClock.uptimeMillis() < launchDeadline) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<IdleProbeActivity>().firstOrNull()
            }
            if (activity == null) SystemClock.sleep(100)
        }
        val fixture = requireNotNull(activity) { "Banner fixture did not resume" }
        val message = context.resources.getQuantityString(R.plurals.migration_health_hint, 2, 2)
        try {
            instrumentation.runOnMainSync {
                val metrics = context.resources.displayMetrics
                val density = minOf(metrics.density, metrics.widthPixels.toFloat() / widthDp)
                fixture.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalUiStrings provides AndroidUiStrings(fixture),
                            LocalDensity provides Density(density, fontScale),
                        ) {
                            Box(Modifier.width(widthDp.dp).background(Color.White)) {
                                SourceHealthBannerContent(
                                    message = message,
                                    onMigrate = { actions += "migrate" },
                                    onDisable = { actions += "disable" },
                                    onDismiss = { actions += "dismiss" },
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val messageNode = awaitNode(instrumentation.uiAutomation, message)
            val messageBounds = Rect()
            messageNode.getBoundsInScreen(messageBounds)
            // A collapsed message is ~30px wide and thousands of pixels tall; a readable one is a line or two of text.
            assertTrue(
                "message is clipped at font $fontScale: $messageBounds",
                messageBounds.width() >= (widthDp * 0.4f * fixture.resources.displayMetrics.density).toInt(),
            )
            assertTrue(
                "message wraps to a letter column at font $fontScale: $messageBounds",
                messageBounds.height() <= messageBounds.width(),
            )
            val expected = listOf(
                context.getString(R.string.migration_title) to "migrate",
                context.getString(R.string.migration_health_banner_disable) to "disable",
            )
            expected.forEach { (label, action) ->
                var node: AccessibilityNodeInfo? = awaitNodeOrNull(instrumentation.uiAutomation, label)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue("$label must be reachable at font $fontScale", node != null)
                assertTrue("$label must accept clicks", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue("$label must dispatch its action", actions.contains(action))
            }
            var close = awaitNodeOrNull(instrumentation.uiAutomation, context.getString(R.string.close))
            while (close != null && !close.isClickable) close = close.parent
            assertTrue("the close button must be reachable at font $fontScale", close != null)
            assertTrue(close!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue("the close button must dispatch its action", actions.contains("dismiss"))
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun awaitNode(
        automation: UiAutomation?,
        label: String,
    ): AccessibilityNodeInfo = requireNotNull(awaitNodeOrNull(automation, label)) {
        "missing [$label]: ${describeTree(automation?.rootInActiveWindow)}"
    }

    private fun awaitNodeOrNull(
        automation: UiAutomation?,
        label: String,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(automation?.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun describeTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val label = node.contentDescription?.toString() ?: node.text?.toString()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildString {
            append("${"  ".repeat(depth)}${node.className} [$label] $bounds\n")
            for (index in 0 until node.childCount) append(describeTree(node.getChild(index), depth + 1))
        }
    }

    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), label)?.let { return it }
        }
        return null
    }
}
