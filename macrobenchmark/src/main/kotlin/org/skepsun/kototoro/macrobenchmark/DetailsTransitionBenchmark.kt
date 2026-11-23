package org.skepsun.kototoro.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * List -> details -> back frames, the transition behind issue #672.
 *
 * Why this exists: the shell records the whole route content into a backdrop layer on every draw while the bottom
 * panel glass (or the edge blur) is on, so a flight over animating content re-renders that layer per frame. This
 * benchmark is the before/after yardstick for the recording throttle in `com.kyant.backdrop`.
 *
 * Preconditions (this drives the real app, not a fixture activity):
 *  - the app is signed in and a source/library has content: pass the title of a work that shows up in the
 *    **favourites list of the last opened space**, and keep the device online so its cover can load;
 *  - animations are on (Settings -> Appearance) and the glass finish the case is meant to cover is enabled;
 *  - nothing else is on screen (no update sheet, no dialogs).
 *
 * Run:
 * ```
 * adb shell wm size reset   # recovery, if a run was aborted while a tablet window was applied
 * ./gradlew :macrobenchmark:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=org.skepsun.kototoro.macrobenchmark.DetailsTransitionBenchmark \
 *   -Pandroid.testInstrumentationRunnerArguments.contentTitle="<title shown in your favourites>"
 * ```
 *
 * The tablet case emulates a sw600dp window with `wm size` / `wm density` instead of requiring a tablet, so the same
 * phone can produce both numbers. It is restored at the start of every iteration and in [restoreWindowSize], so a
 * failing iteration cannot leak a resized window into the next test.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DetailsTransitionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val contentTitle: String by lazy {
        InstrumentationRegistry.getArguments().getString(ARG_CONTENT_TITLE)
            ?: fail(
                "Pass -Pandroid.testInstrumentationRunnerArguments.$ARG_CONTENT_TITLE=\"<title in your favourites>\"; " +
                    "the benchmark needs a work it can open from the list.",
            ).let { "" }
    }

    private val iterations: Int by lazy {
        InstrumentationRegistry.getArguments().getString(ARG_ITERATIONS)?.toIntOrNull()?.coerceAtLeast(1)
            ?: DEFAULT_ITERATIONS
    }

    /** Two-pane / wide window: the case the issue is worst on. */
    @Test
    fun detailsTransitionTablet() = measureDetailsTransition(
        isTabletWindow = true,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    /** Same flight on the phone layout: the no-regression counterpart. */
    @Test
    fun detailsTransitionPhone() = measureDetailsTransition(
        isTabletWindow = false,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @After
    fun restoreWindowSize() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("wm size reset")
        device.executeShellCommand("wm density reset")
    }

    private fun measureDetailsTransition(isTabletWindow: Boolean, compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = null,
            iterations = iterations,
            setupBlock = {
                // Idempotent: an aborted previous run may have left a resized window behind.
                resetWindow(this)
                if (isTabletWindow) applyTabletWindow(this)
                startApp(this)
                waitForList(this)
            },
        ) {
            openDetailsAndBack(this)
        }
    }

    private fun startApp(scope: MacrobenchmarkScope) {
        val intent = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        scope.startActivityAndWait(intent)
    }

    /**
     * How to find the configured work in the list. The card puts the *whole* title in its content description while
     * the visible line is ellipsized after two lines, so the description is exact and the text is only a fallback
     * for layouts that build their own semantics.
     */
    private fun itemSelectors(): List<BySelector> = listOf(
        By.desc(contentTitle),
        By.textStartsWith(contentTitle.take(TITLE_PREFIX_LENGTH)),
    )

    private fun waitForItem(device: UiDevice, timeoutMs: Long): Boolean = itemSelectors().any {
        device.wait(Until.hasObject(it), timeoutMs)
    }

    /** Scrolls the favourites grid until the configured title shows up, then leaves the list on screen. */
    private fun waitForList(scope: MacrobenchmarkScope) {
        if (waitForItem(scope.device, STARTUP_TIMEOUT_MS)) return
        repeat(LIST_SCROLL_ATTEMPTS) {
            scope.device.swipe(
                scope.device.displayWidth / 2,
                (scope.device.displayHeight * 0.75f).toInt(),
                scope.device.displayWidth / 2,
                (scope.device.displayHeight * 0.25f).toInt(),
                SWIPE_STEPS,
            )
            if (waitForItem(scope.device, LIST_WAIT_MS)) return
        }
        fail(
            "Could not find \"$contentTitle\" in the favourites list. Seed the last opened space with that work, " +
                "or pass another title via -Pandroid.testInstrumentationRunnerArguments.$ARG_CONTENT_TITLE.",
        )
    }

    /**
     * One flight: tap the work, let the details page settle, go back, let the list settle. The settle windows are
     * part of the measurement on purpose - a frame dropped while the hero settles is exactly what #672 is about.
     */
    private fun findItem(device: UiDevice): UiObject2 =
        itemSelectors().asSequence().mapNotNull { device.findObject(it) }.firstOrNull()
            ?: throw AssertionError(
                "The list item \"$contentTitle\" disappeared between setup and measurement.",
            )

    private fun openDetailsAndBack(scope: MacrobenchmarkScope) {
        findItem(scope.device).click()
        SystemClock.sleep(DETAILS_SETTLE_MS)
        scope.device.pressBack()
        SystemClock.sleep(LIST_SETTLE_MS)
        scope.device.waitForIdle()
    }

    private fun applyTabletWindow(scope: MacrobenchmarkScope) {
        scope.device.executeShellCommand("wm size ${TABLET_WIDTH_PX}x${TABLET_HEIGHT_PX}")
        scope.device.executeShellCommand("wm density $TABLET_DENSITY_DPI")
        scope.device.waitForIdle()
    }

    private fun resetWindow(scope: MacrobenchmarkScope) {
        scope.device.executeShellCommand("wm size reset")
        scope.device.executeShellCommand("wm density reset")
    }

    private companion object {
        const val TARGET_PACKAGE = "org.skepsun.kototoro.debug"
        const val TARGET_ACTIVITY = "org.skepsun.kototoro.main.ui.MainActivity"

        /** Instrumentation argument, not a preference: the harness cannot read the app's database. */
        const val ARG_CONTENT_TITLE = "contentTitle"

        /** Smoke runs pass 1; a measurement run keeps the default so the median has something to work with. */
        const val ARG_ITERATIONS = "iterations"
        const val DEFAULT_ITERATIONS = 5

        /** Fallback match only: the grid ellipsizes the visible title, so a prefix short enough to survive truncation. */
        const val TITLE_PREFIX_LENGTH = 12

        const val STARTUP_TIMEOUT_MS = 20_000L
        const val LIST_WAIT_MS = 3_000L
        const val LIST_SCROLL_ATTEMPTS = 3
        const val SWIPE_STEPS = 12

        /** 800 x 1280 dp at 320 dpi: the two-pane breakpoint without needing a physical tablet. */
        const val TABLET_WIDTH_PX = 2_560
        const val TABLET_HEIGHT_PX = 1_600
        const val TABLET_DENSITY_DPI = 320

        const val DETAILS_SETTLE_MS = 700L
        const val LIST_SETTLE_MS = 500L
    }
}
