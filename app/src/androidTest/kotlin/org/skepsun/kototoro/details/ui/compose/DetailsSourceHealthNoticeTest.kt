package org.skepsun.kototoro.details.ui.compose

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
 * The source-health warning used to be a long snackbar pinned over the bottom dock, and the page
 * posted a second snackbar for the same cause while it was up. It is now an inline notice: it must
 * stay readable at real font scales and stay small enough that it cannot cover the primary action.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class DetailsSourceHealthNoticeTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun noticeStaysReadableAtDefaultFont() = verifyNotice(fontScale = 1f)

    @Test
    fun noticeStaysReadableAtLargeFont() = verifyNotice(fontScale = 1.5f)

    @Test
    fun noticeStaysReadableAtLargestFont() = verifyNotice(fontScale = 2f)

    private fun verifyNotice(fontScale: Float) {
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
        val fixture = requireNotNull(activity) { "Notice fixture did not resume" }
        val message = context.getString(R.string.migration_health_details_warning, context.getString(R.string.migration_health_uninstalled))
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
                                DetailsSourceHealthNotice(
                                    message = message,
                                    onMigrate = { actions += "migrate" },
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            val messageNode = awaitNode(automation, message)
            val messageBounds = Rect()
            messageNode.getBoundsInScreen(messageBounds)
            assertTrue(
                "notice text is clipped at font $fontScale: $messageBounds",
                messageBounds.width() >= (widthDp * 0.4f * fixture.resources.displayMetrics.density).toInt(),
            )
            assertTrue(
                "notice text wraps to a letter column at font $fontScale: $messageBounds",
                messageBounds.height() <= messageBounds.width(),
            )
            var migrate: AccessibilityNodeInfo? = awaitNodeOrNull(automation, context.getString(R.string.migrate))
            while (migrate != null && !migrate.isClickable) migrate = migrate.parent
            assertTrue("Migrate must be reachable at font $fontScale", migrate != null)
            val migrateBounds = Rect()
            migrate!!.getBoundsInScreen(migrateBounds)
            // The notice sits at the top of the scrollable content; nothing here may be tall enough to
            // reach the bottom dock (the phone is 2400px tall, the dock occupies the last ~340px).
            assertTrue(
                "notice must stay a compact card at font $fontScale: $messageBounds",
                messageBounds.bottom <= (fixture.resources.displayMetrics.heightPixels * 0.6f).toInt(),
            )
            assertTrue("Migrate must accept clicks", migrate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue("Migrate must dispatch its action", actions.contains("migrate"))
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun awaitNode(automation: UiAutomation?, label: String): AccessibilityNodeInfo =
        requireNotNull(awaitNodeOrNull(automation, label)) {
            "missing [$label]: ${describeTree(automation?.rootInActiveWindow)}"
        }

    private fun awaitNodeOrNull(automation: UiAutomation?, label: String): AccessibilityNodeInfo? {
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
