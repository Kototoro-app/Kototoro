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
import org.skepsun.kototoro.core.prefs.ReaderBackground
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

    @Test
    fun doubleTapZoomsContent() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val fixture = File(context.cacheDir, "scene-paged-zoom-test.png")
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val pages = listOf(
            ReaderPage(1L, fixture.toURI().toString(), null, null, 1, 0, LocalMangaSource),
        )
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
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                zoomMode = ZoomMode.FIT_CENTER,
                                isAnimationEnabled = false,
                                readerBackground = ReaderBackground.BLACK,
                            )
                        }
                    }
                }
                waitUntil {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                    screenshot.recycle()
                    activePage.get() == pages.first().readerKey && Color.red(pixel) > 220
                }

                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val cx = screenshot.width / 2f
                val cy = screenshot.height / 2f
                screenshot.recycle()

                // Inject double tap at screen center
                fun tap(downTime: Long) {
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                    instrumentation.sendPointerSync(down)
                    val up = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, cx, cy, 0)
                    instrumentation.sendPointerSync(up)
                    down.recycle()
                    up.recycle()
                }

                val t0 = SystemClock.uptimeMillis()
                tap(t0)
                SystemClock.sleep(60)
                tap(t0 + 70)
                instrumentation.waitForIdleSync()

                // Verify that after zooming to 2.5x, the 200px red square expands so that (cx + 180) is red
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val testPixel = shot.getPixel((cx + 180f).toInt(), cy.toInt())
                    shot.recycle()
                    Color.red(testPixel) > 220
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun zoomedPageRemainsZoomedDuringPageFlip() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val redBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blueBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val redFile = File(context.cacheDir, "scene-paged-red.png")
        val blueFile = File(context.cacheDir, "scene-paged-blue.png")
        redFile.outputStream().use { redBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        blueFile.outputStream().use { blueBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        redBmp.recycle()
        blueBmp.recycle()

        val pages = listOf(
            ReaderPage(1L, redFile.toURI().toString(), null, null, 1, 0, LocalMangaSource),
            ReaderPage(2L, blueFile.toURI().toString(), null, null, 1, 1, LocalMangaSource),
        )
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = flowOf(
                ComposeReaderImageState.OriginalReady(
                    Uri.fromFile(if (page.index == 0) redFile else blueFile)
                )
            )
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
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                zoomMode = ZoomMode.FIT_CENTER,
                                isAnimationEnabled = false,
                                readerBackground = ReaderBackground.BLACK,
                            )
                        }
                    }
                }
                waitUntil {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                    screenshot.recycle()
                    activePage.get() == pages.first().readerKey && Color.red(pixel) > 220
                }

                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val cx = screenshot.width / 2f
                val cy = screenshot.height / 2f
                screenshot.recycle()

                fun tap() {
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                    instrumentation.sendPointerSync(down)
                    down.recycle()
                    SystemClock.sleep(20)
                    val upTime = SystemClock.uptimeMillis()
                    val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, cx, cy, 0)
                    instrumentation.sendPointerSync(up)
                    up.recycle()
                }

                tap()
                SystemClock.sleep(100)
                tap()
                instrumentation.waitForIdleSync()

                // Verify page 1 is zoomed (pixel cx + 180 is red)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val testPixel = shot.getPixel((cx + 180f).toInt(), cy.toInt())
                    shot.recycle()
                    Color.red(testPixel) > 220
                }

                // Swipe until boundary is reached and page flips to page 2
                fun swipe() {
                    val swipeStart = SystemClock.uptimeMillis()
                    val startX = cx + 300f
                    val endX = cx - 300f
                    for (step in 0..10) {
                        val curX = startX + (endX - startX) * step / 10f
                        val now = swipeStart + step * 25
                        SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0))
                        val action = when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            10 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        }
                        val event = MotionEvent.obtain(swipeStart, now, action, curX, cy, 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                }

                var attempts = 0
                while (activePage.get() == pages.first().readerKey && attempts++ < 10) {
                    swipe()
                }

                // Wait until settled on page 2 (blue)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val centerPixel = shot.getPixel(cx.toInt(), cy.toInt())
                    shot.recycle()
                    activePage.get() == pages[1].readerKey && Color.blue(centerPixel) > 220
                }
                assertEquals(pages[1].readerKey, activePage.get())
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun zoomedPageRestoresZoomWhenFlippingBack() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val redBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blueBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val redFile = File(context.cacheDir, "scene-paged-restore-red.png")
        val blueFile = File(context.cacheDir, "scene-paged-restore-blue.png")
        redFile.outputStream().use { redBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        blueFile.outputStream().use { blueBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        redBmp.recycle()
        blueBmp.recycle()

        val pages = listOf(
            ReaderPage(1L, redFile.toURI().toString(), null, null, 1, 0, LocalMangaSource),
            ReaderPage(2L, blueFile.toURI().toString(), null, null, 1, 1, LocalMangaSource),
        )
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = flowOf(
                ComposeReaderImageState.OriginalReady(
                    Uri.fromFile(if (page.index == 0) redFile else blueFile)
                )
            )
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
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                zoomMode = ZoomMode.FIT_CENTER,
                                isAnimationEnabled = false,
                                readerBackground = ReaderBackground.BLACK,
                            )
                        }
                    }
                }
                waitUntil {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                    screenshot.recycle()
                    activePage.get() == pages.first().readerKey && Color.red(pixel) > 220
                }

                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val cx = screenshot.width / 2f
                val cy = screenshot.height / 2f
                screenshot.recycle()

                fun tap() {
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
                    instrumentation.sendPointerSync(down)
                    down.recycle()
                    SystemClock.sleep(20)
                    val upTime = SystemClock.uptimeMillis()
                    val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, cx, cy, 0)
                    instrumentation.sendPointerSync(up)
                    up.recycle()
                }

                tap()
                SystemClock.sleep(100)
                tap()
                instrumentation.waitForIdleSync()

                // Verify page 1 is zoomed (pixel cx + 180 is red)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val testPixel = shot.getPixel((cx + 180f).toInt(), cy.toInt())
                    shot.recycle()
                    Color.red(testPixel) > 220
                }

                // Swipe until boundary is reached and page flips to page 2
                fun swipeForward() {
                    val swipeStart = SystemClock.uptimeMillis()
                    val startX = cx + 300f
                    val endX = cx - 300f
                    for (step in 0..10) {
                        val curX = startX + (endX - startX) * step / 10f
                        val now = swipeStart + step * 25
                        SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0))
                        val action = when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            10 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        }
                        val event = MotionEvent.obtain(swipeStart, now, action, curX, cy, 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                }

                var attempts = 0
                while (activePage.get() == pages.first().readerKey && attempts++ < 10) {
                    swipeForward()
                }

                // Wait until settled on page 2 (blue)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val centerPixel = shot.getPixel(cx.toInt(), cy.toInt())
                    shot.recycle()
                    activePage.get() == pages[1].readerKey && Color.blue(centerPixel) > 220
                }
                assertEquals(pages[1].readerKey, activePage.get())

                // Swipe back from page 2 to page 1
                fun swipeBackward() {
                    val swipeStart = SystemClock.uptimeMillis()
                    val startX = cx - 300f
                    val endX = cx + 300f
                    for (step in 0..10) {
                        val curX = startX + (endX - startX) * step / 10f
                        val now = swipeStart + step * 25
                        SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0))
                        val action = when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            10 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        }
                        val event = MotionEvent.obtain(swipeStart, now, action, curX, cy, 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                }

                attempts = 0
                while (activePage.get() == pages[1].readerKey && attempts++ < 10) {
                    swipeBackward()
                }

                // Wait until settled back on page 1 (red)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val centerPixel = shot.getPixel(cx.toInt(), cy.toInt())
                    shot.recycle()
                    activePage.get() == pages[0].readerKey && Color.red(centerPixel) > 220
                }
                assertEquals(pages[0].readerKey, activePage.get())

                // Verify page 1 is STILL zoomed at 2.5x (pixel cx + 180 is red!)
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val testPixel = shot.getPixel((cx + 180f).toInt(), cy.toInt())
                    shot.recycle()
                    Color.red(testPixel) > 220
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

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
