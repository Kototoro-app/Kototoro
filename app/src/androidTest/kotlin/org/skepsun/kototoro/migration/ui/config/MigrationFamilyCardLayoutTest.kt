package org.skepsun.kototoro.migration.ui.config

import android.app.UiAutomation
import android.graphics.Bitmap
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.ui.text.AndroidUiStrings
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * With no enabled source of that content type the family card said "No enabled sources of this type
 * yet" and offered "Manage content sources" in the same row. At 1.5× the sentence was squeezed into a
 * one-word column and the card grew taller than the sheet, so the sentence and the action have to be
 * measured, not assumed.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MigrationFamilyCardLayoutTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun emptyFamilyStaysReadableAtDefaultFont() = verifyCard(fontScale = 1f)

    @Test
    fun emptyFamilyStaysReadableAtLargeFont() = verifyCard(fontScale = 1.5f)

    @Test
    fun emptyFamilyStaysReadableAtLargestFont() = verifyCard(fontScale = 2f)

    private fun verifyCard(fontScale: Float) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val widthDp = 411
        val actions = CopyOnWriteArrayList<String>()
        val message = context.getString(R.string.migration_no_available_sources)
        val action = context.getString(R.string.manage_sources)
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
        val fixture = requireNotNull(activity) { "Migration card fixture did not resume" }
        val density = context.resources.displayMetrics.density
        try {
            instrumentation.runOnMainSync {
                val metrics = context.resources.displayMetrics
                val clamped = minOf(metrics.density, metrics.widthPixels.toFloat() / widthDp)
                fixture.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalUiStrings provides AndroidUiStrings(fixture),
                            LocalDensity provides Density(clamped, fontScale),
                        ) {
                            Box(Modifier.width(widthDp.dp).background(Color.White)) {
                                FamilySourcesCard(
                                    family = FamilySources(
                                        family = ContentTypeFamily.MANGA,
                                        available = emptyList(),
                                        pinned = emptySet(),
                                        selected = emptyList(),
                                        contentCount = 2,
                                    ),
                                    showFamilyLabel = true,
                                    onClick = { actions += "pick" },
                                    onManageSources = { actions += "manage" },
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            val messageBounds = boundsOf(automation, message)
            assertTrue(
                "message is squeezed into a column at font $fontScale: $messageBounds",
                messageBounds.width() >= messageBounds.height(),
            )
            assertTrue(
                "message must use most of the card at font $fontScale: $messageBounds",
                messageBounds.width() >= (widthDp * 0.5f * density).toInt(),
            )
            val actionBounds = boundsOf(automation, action)
            assertTrue(
                "the action must sit below the sentence at font $fontScale, got $actionBounds under $messageBounds",
                actionBounds.top >= messageBounds.bottom - 2,
            )
            // The card is one tappable surface; with no sources it must offer the manager.
            var node: AccessibilityNodeInfo? = awaitNodeOrNull(automation, message)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("card must be clickable at font $fontScale", node != null)
            assertTrue("card must dispatch its action", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue("expected the manage action at font $fontScale, got $actions", actions.contains("manage"))
            SystemClock.sleep(600)
            val shotName = "migration-family-card-${fontScale}.png"
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("screencap -p /sdcard/Download/$shotName"),
            ).use { it.readBytes() }
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun boundsOf(automation: UiAutomation, label: String): Rect {
        val node = requireNotNull(awaitNodeOrNull(automation, label)) {
            "missing [$label]: ${describeTree(automation.rootInActiveWindow)}"
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
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
