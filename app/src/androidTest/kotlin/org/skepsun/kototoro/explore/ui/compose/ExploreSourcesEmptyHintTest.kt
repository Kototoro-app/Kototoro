package org.skepsun.kototoro.explore.ui.compose

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
import org.skepsun.kototoro.core.extensions.SourceEmptyHintKind
import org.skepsun.kototoro.list.ui.model.EmptyHint

/**
 * The Explore tab used to render a skeleton and then a blank area when no source was available: the view model already
 * produced an [EmptyHint], but the page dropped it. These checks keep both empty states readable and actionable at real
 * font scales, and pin which action each kind owes the user — an empty library opens the extension manager, an
 * over-filtered page resets the filters.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ExploreSourcesEmptyHintTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun noSourcesAtLargeFontOffersTheExtensionManager() = verifyHint(
        fontScale = 1.5f,
        kind = SourceEmptyHintKind.NO_SOURCES,
        titleRes = R.string.no_manga_sources,
        messageRes = R.string.no_manga_sources_text,
        actionRes = R.string.welcome_open_extension_management,
        expectedAction = "manage",
    )

    @Test
    fun noSourcesAtLargestFontStaysReadable() = verifyHint(
        fontScale = 2f,
        kind = SourceEmptyHintKind.NO_SOURCES,
        titleRes = R.string.no_manga_sources,
        messageRes = R.string.no_manga_sources_text,
        actionRes = R.string.welcome_open_extension_management,
        expectedAction = "manage",
    )

    @Test
    fun hiddenByFiltersAtLargeFontOffersAReset() = verifyHint(
        fontScale = 1.5f,
        kind = SourceEmptyHintKind.HIDDEN_BY_FILTERS,
        titleRes = R.string.no_sources_found,
        messageRes = R.string.no_sources_found_desc,
        actionRes = R.string.reset_filter,
        expectedAction = "reset",
    )

    private fun verifyHint(
        fontScale: Float,
        kind: SourceEmptyHintKind,
        titleRes: Int,
        messageRes: Int,
        actionRes: Int,
        expectedAction: String,
    ) {
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
        val fixture = requireNotNull(activity) { "Empty-hint fixture did not resume" }
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
                                ExploreSourcesEmptyHint(
                                    hint = EmptyHint(
                                        icon = R.drawable.ic_empty_common,
                                        textPrimary = titleRes,
                                        textSecondary = messageRes,
                                        actionStringRes = actionRes,
                                        kind = kind,
                                    ),
                                    onCreateShortcut = { actions += "manage" },
                                    onResetFilters = { actions += "reset" },
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            val title = awaitNode(automation, context.getString(titleRes))
            val bounds = Rect()
            title.getBoundsInScreen(bounds)
            assertTrue(
                "title must not collapse at font $fontScale: $bounds",
                bounds.width() >= (widthDp * 0.4f * fixture.resources.displayMetrics.density).toInt(),
            )
            val message = awaitNode(automation, context.getString(messageRes))
            message.getBoundsInScreen(bounds)
            assertTrue(
                "message must stay a paragraph at font $fontScale: $bounds",
                bounds.width() >= (widthDp * 0.4f * fixture.resources.displayMetrics.density).toInt() &&
                    bounds.height() <= bounds.width(),
            )
            var action: AccessibilityNodeInfo? = awaitNodeOrNull(automation, context.getString(actionRes))
            while (action != null && !action.isClickable) action = action.parent
            assertTrue("action must be reachable at font $fontScale", action != null)
            assertTrue("action must accept clicks", action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue(
                "the ${kind.name} hint must dispatch $expectedAction, got $actions",
                actions.contains(expectedAction),
            )
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
