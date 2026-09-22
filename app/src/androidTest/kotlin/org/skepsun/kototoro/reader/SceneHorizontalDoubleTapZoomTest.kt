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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneHorizontalReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the production pointer-input handler of the horizontal continuous scene reader.
 *
 * The double-tap zoom of the horizontal mode is the same interaction contract as the webtoon
 * reader's: a double tap toggles the canvas between the default scale and 2x, keeping the tapped
 * point stationary. The fixture is two narrow pages (aspect 100:2000) laid out side by side, so
 * the page boundary lands at a computable screen position; zooming to 2x about a double tap on
 * that boundary must double the blue page's on-screen extent.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneHorizontalDoubleTapZoomTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun doubleTapZoomsHorizontalContent() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val redBmp = Bitmap.createBitmap(100, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blueBmp = Bitmap.createBitmap(100, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val redFile = File(context.cacheDir, "scene-horizontal-zoom-red.png")
        val blueFile = File(context.cacheDir, "scene-horizontal-zoom-blue.png")
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
        val bounds = AtomicReference<Rect>()
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
                            ComposeSceneHorizontalReader(
                                pages = pages,
                                initialPage = 0,
                                initialScroll = 0,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                isZoomEnabled = true,
                                isAnimationEnabled = false,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                onInternalScrollChanged = { _, _ -> },
                                modifier = Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                            )
                        }
                    }
                }
                waitUntil {
                    val area = bounds.get() ?: return@waitUntil false
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    // Pages fit to the viewport height with aspect 100:2000 -> each is H/20 wide.
                    val pageWidth = area.height / 20f
                    val pixel = shot.getPixel(
                        (area.left + pageWidth / 2f).toInt(),
                        (area.top + area.height / 2f).toInt(),
                    )
                    shot.recycle()
                    activePage.get() == pages.first().readerKey && Color.red(pixel) > 220
                }
                val area = checkNotNull(bounds.get())
                val pageWidth = area.height / 20f
                val boundaryX = area.left + pageWidth
                val cy = area.top + area.height / 2f

                fun tap(downTime: Long) {
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, boundaryX, cy, 0)
                    instrumentation.sendPointerSync(down)
                    val up = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, boundaryX, cy, 0)
                    instrumentation.sendPointerSync(up)
                    down.recycle()
                    up.recycle()
                }

                val t0 = SystemClock.uptimeMillis()
                tap(t0)
                SystemClock.sleep(60)
                tap(t0 + 70)
                instrumentation.waitForIdleSync()

                // Zooming to 2x about the page boundary doubles the blue page's extent from
                // [boundary, 2*boundary] to [boundary, 3*boundary] in screen space.
                waitUntil {
                    val shot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                    val pixel = shot.getPixel(
                        (area.left + pageWidth * 2.5f).toInt(),
                        (area.top + area.height / 2f).toInt(),
                    )
                    shot.recycle()
                    Color.blue(pixel) > 220
                }
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
        assertTrue("Horizontal double-tap zoom fixture did not zoom", false)
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
