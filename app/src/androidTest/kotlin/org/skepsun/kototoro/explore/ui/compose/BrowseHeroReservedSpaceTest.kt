package org.skepsun.kototoro.explore.ui.compose

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.text.AndroidUiStrings
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import javax.inject.Inject

/**
 * The browse hero is an overlay: the list reserves its height so the first row scrolls out from under
 * it. With recommendations enabled but nothing to show (no tracking service, offline, empty rows) the
 * block drew an empty gradient band and the reservation left ~700px of dead space above the sources.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class BrowseHeroReservedSpaceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settings: AppSettings

    @Test
    fun emptyHeroTakesNoSpace() = verifyHeroHeight(isDiscoverLoading = false, expectHero = false)

    @Test
    fun loadingHeroStillReservesTheSkeleton() = verifyHeroHeight(isDiscoverLoading = true, expectHero = true)

    private fun verifyHeroHeight(isDiscoverLoading: Boolean, expectHero: Boolean) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val widthDp = 411
        val marker = "below-hero-marker"
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
        val fixture = requireNotNull(activity) { "Hero fixture did not resume" }
        val density = context.resources.displayMetrics.density
        try {
            instrumentation.runOnMainSync {
                val metrics = context.resources.displayMetrics
                val clamped = minOf(metrics.density, metrics.widthPixels.toFloat() / widthDp)
                fixture.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalUiStrings provides AndroidUiStrings(fixture),
                            LocalDensity provides Density(clamped, 1f),
                        ) {
                            Box(Modifier.width(widthDp.dp).background(Color.White)) {
                                Column(Modifier.fillMaxWidth()) {
                                    BrowseHeroBlock(
                                        title = "Discover",
                                        heroItems = emptyList(),
                                        activeService = null,
                                        availableServices = emptyList(),
                                        isLoadingOnly = isDiscoverLoading,
                                        topContentInset = 0.dp,
                                        settings = settings,
                                        onSelectService = {},
                                        onHeroItemClick = { _, _ -> },
                                        sharedElementKeyForItem = { _, index -> "hero-$index" },
                                    )
                                    Text(marker)
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val automation: UiAutomation = instrumentation.uiAutomation
            val markerNode = requireNotNull(awaitNode(automation, marker)) {
                "missing [$marker]: ${describeTree(automation.rootInActiveWindow)}"
            }
            val bounds = Rect()
            markerNode.getBoundsInScreen(bounds)
            val topInDp = bounds.top / density
            if (expectHero) {
                // The skeleton keeps the reserved band, so the content below it starts well down.
                assertTrue(
                    "loading hero must keep its reserved band, marker top was ${topInDp}dp",
                    topInDp >= 200f,
                )
            } else {
                assertTrue(
                    "empty hero must reserve no space, marker top was ${topInDp}dp",
                    topInDp <= 80f,
                )
            }
            // Keep a real screenshot of the case: bounds alone cannot show the dead band. screencap
            // grabs the actual display, and Downloads survives the uninstall that follows this run.
            SystemClock.sleep(600)
            val shotName = if (expectHero) "browse-hero-loading.png" else "browse-hero-empty.png"
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("screencap -p /sdcard/Download/$shotName"),
            ).use { it.readBytes() }
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun awaitNode(automation: UiAutomation?, label: String): AccessibilityNodeInfo? {
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
