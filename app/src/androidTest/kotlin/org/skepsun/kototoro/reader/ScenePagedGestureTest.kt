package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Drives the production pointer-input handler and image pipeline with local overflow fixtures. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedGestureTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun horizontalOverflowPanHandsOffToPageTurn() = verifyOverflow(SceneReadingDirection.LEFT_TO_RIGHT)

    @Test
    fun rtlOverflowPanHandsOffToPageTurn() = verifyOverflow(SceneReadingDirection.RIGHT_TO_LEFT)

    @Test
    fun verticalOverflowPanHandsOffToPageTurn() = verifyOverflow(SceneReadingDirection.TOP_TO_BOTTOM)

    private fun verifyOverflow(direction: SceneReadingDirection) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vertical = direction.isVertical
        val bitmap = Bitmap.createBitmap(
            if (vertical) 300 else 1800,
            if (vertical) 2400 else 600,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.RED)
        val fixture = File(context.cacheDir, "scene-paged-overflow-${direction.name}.png")
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val pages = (1L..3L).map { id ->
            ReaderPage(id, fixture.toURI().toString(), null, null, 1, id.toInt() - 1, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fixture)))
        }
        val activePage = AtomicLong(Long.MIN_VALUE)
        val imageLoader = ImageLoader(context)
        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            ComposeScenePagedReader(
                                pages = pages,
                                initialPage = 0,
                                readingDirection = direction,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                zoomMode = if (vertical) ZoomMode.FIT_WIDTH else ZoomMode.FIT_HEIGHT,
                                isZoomEnabled = true,
                                isAnimationEnabled = false,
                            )
                        }
                    }
                }
                waitUntil {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                    screenshot.recycle()
                    activePage.get() == pages.first().readerKey && Color.red(pixel) > 220 && Color.green(pixel) < 40
                }
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val width = screenshot.width.toFloat()
                val height = screenshot.height.toFloat()
                screenshot.recycle()
                fun swipe(duration: Long) {
                    val reversed = direction == SceneReadingDirection.RIGHT_TO_LEFT
                    val start = if (reversed) 0.25f else 0.75f
                    val end = if (reversed) 0.75f else 0.25f
                    val downAt = SystemClock.uptimeMillis()
                    for (step in 0..10) {
                        val fraction = start + (end - start) * step / 10f
                        val eventAt = downAt + duration * step / 10
                        SystemClock.sleep((eventAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
                        val action = when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            10 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        }
                        val event = MotionEvent.obtain(
                            downAt, eventAt, action,
                            if (vertical) width / 2 else width * fraction,
                            if (vertical) height * fraction else height / 2,
                            0,
                        )
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                }

                swipe(80)
                assertEquals("Fast content pan must not fling the page", pages.first().readerKey, activePage.get())
                var attempts = 0
                while (activePage.get() == pages.first().readerKey && attempts++ < 24) swipe(300)
                assertEquals("Overflow edge must hand off to the next page", pages[1].readerKey, activePage.get())
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (SystemClock.uptimeMillis() < deadline) {
            dismissSystemDialogs(instrumentation)
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Paged fixture did not become visible", false)
    }

    private fun dismissSystemDialogs(instrumentation: android.app.Instrumentation) {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg == "android" || pkg?.contains("systemui") == true) {
            val button = root.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
                ?: root.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}
