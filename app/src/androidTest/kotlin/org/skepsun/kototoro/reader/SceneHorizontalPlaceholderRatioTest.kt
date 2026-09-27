package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.flow.emptyFlow
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/**
 * Chapter-ratio convergence of the loading placeholder geometry in the horizontal continuous
 * scene reader.
 *
 * A page that has not decoded yet cannot know its real size, but pages within a chapter share
 * their aspect ratio closely: once two sibling pages have decoded, the still-loading page's
 * placeholder must adopt the chapter-average ratio (fit-height width = viewport height / 4 for
 * the 1:4 fixture) instead of the fallback viewport-width square. The fixture snaps to the
 * never-loading third page; a placeholder is painted with the reader background, so its width is
 * measured as the background run between it and the decoded fourth page.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneHorizontalPlaceholderRatioTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun loadingPlaceholderAdoptsChapterRatio() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val greenBmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val cyanBmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val magentaBmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val greenFile = File(context.cacheDir, "scene-placeholder-ratio-green.png")
        val cyanFile = File(context.cacheDir, "scene-placeholder-ratio-cyan.png")
        val magentaFile = File(context.cacheDir, "scene-placeholder-ratio-magenta.png")
        greenFile.outputStream().use { greenBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cyanFile.outputStream().use { cyanBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        magentaFile.outputStream().use { magentaBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        greenBmp.recycle()
        cyanBmp.recycle()
        magentaBmp.recycle()

        val pages = listOf(
            ReaderPage(1L, greenFile.toURI().toString(), null, null, 1, 0, LocalMangaSource),
            ReaderPage(2L, cyanFile.toURI().toString(), null, null, 1, 1, LocalMangaSource),
            ReaderPage(3L, "about:blank", null, null, 1, 2, LocalMangaSource),
            ReaderPage(4L, magentaFile.toURI().toString(), null, null, 1, 3, LocalMangaSource),
        )
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = if (page.index == 2) {
                emptyFlow()
            } else {
                flowOf(
                    ComposeReaderImageState.OriginalReady(
                        Uri.fromFile(
                            when (page.index) {
                                0 -> greenFile
                                1 -> cyanFile
                                else -> magentaFile
                            },
                        ),
                    ),
                )
            }
        }
        val bounds = AtomicReference<Rect>()
        val requestedPage = mutableStateOf<Int?>(null)
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
                                requestedPage = requestedPage.value,
                                onPagesChanged = { _, _, _ -> },
                                onInternalScrollChanged = { _, _ -> },
                                modifier = Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                            )
                        }
                    }
                }

                // Wait until both visible pages have decoded: no placeholder remains on screen.
                waitUntil {
                    val area = bounds.get() ?: return@waitUntil false
                    measureWidestPlaceholderRun(instrumentation, area) == 0f
                }
                val area = checkNotNull(bounds.get())

                // Snap to the never-loading third page.
                scenario.onActivity { requestedPage.value = 2 }
                instrumentation.waitForIdleSync()

                // The placeholder of the third page must be fit-height sized by the chapter
                // ratio: viewportHeight / 4 (1:4 pages), NOT the fallback viewport-width square.
                val expectedWidth = area.height / 4f
                waitUntil {
                    val measured = measureWidestPlaceholderRun(instrumentation, area)
                    abs(measured - expectedWidth) < expectedWidth * 0.12f
                }
                val measured = measureWidestPlaceholderRun(instrumentation, area)
                assertTrue(
                    "placeholder width $measured should match chapter ratio width $expectedWidth",
                    abs(measured - expectedWidth) < expectedWidth * 0.12f,
                )
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    /**
     * Widest contiguous run of reader-background (black) pixels across a row above the center, clear
     * of the centered loading indicator. Decoded pages are green/cyan/magenta, so a black run is a
     * placeholder, bounded by decoded pages on both sides once the fixture has settled.
     */
    private fun measureWidestPlaceholderRun(
        instrumentation: android.app.Instrumentation,
        area: Rect,
    ): Float {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return -1f
        val row = (area.top + area.height * 0.35f).toInt()
        var widest = 0
        var current = 0
        for (x in area.left.toInt() until area.right.toInt()) {
            val pixel = shot.getPixel(x, row)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val isPlaceholder = max(r, max(g, b)) < 24
            if (isPlaceholder) {
                current++
                if (current > widest) widest = current
            } else {
                current = 0
            }
        }
        shot.recycle()
        return widest.toFloat()
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (SystemClock.uptimeMillis() < deadline) {
            dismissSystemDialogs(instrumentation)
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Horizontal placeholder ratio fixture did not converge", false)
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
