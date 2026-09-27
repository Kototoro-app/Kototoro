package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneWebtoonReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Scrolling across a page that never finishes loading in the webtoon scene reader.
 *
 * The loading page is painted with the reader background and carries the legacy reader's loading
 * indicator. Every drag here starts on that indicator: the indicator is an AndroidView, and while
 * the overlays were siblings above the renderer it won the hit test and swallowed the drag, so the
 * strip could not be scrolled past the loading page.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneWebtoonLoadingPlaceholderTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun dragsStartingOnTheLoadingIndicatorScrollPastTheLoadingPage() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf(Color.GREEN, Color.CYAN, Color.MAGENTA).mapIndexed { i, color ->
            val bmp = Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            File(context.cacheDir, "scene-loading-placeholder-$i.png").also { file ->
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
        // Page 3 (index 2) never finishes loading; page 4 decodes to magenta.
        val fileFor = { index: Int -> files[if (index >= 3) 2 else index] }
        val pages = (0 until 4).map { i ->
            val url = if (i == 2) "about:blank" else fileFor(i).toURI().toString()
            ReaderPage(i + 1L, url, null, null, 1, i, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = if (page.index == 2) {
                flow<ComposeReaderImageState> { awaitCancellation() }
            } else {
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fileFor(page.index))))
            }
        }
        val bounds = AtomicReference<Rect>()
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
                            ComposeSceneWebtoonReader(
                                pages = pages,
                                initialPage = 2,
                                initialScroll = 0,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, _ -> },
                                onInternalScrollChanged = { _, _ -> },
                                modifier = Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                            )
                        }
                    }
                }
                val indicatorY = waitForIndicator(instrumentation, bounds)
                assertNotNull("the loading page must show the loading indicator", indicatorY)
                val area = checkNotNull(bounds.get())
                val x = area.center.x.toInt()
                val distance = (area.height * 0.3f).toInt()
                var reachedNextPage = false
                repeat(12) {
                    if (reachedNextPage) return@repeat
                    // Start every drag on the indicator, wherever it currently is on screen.
                    val startY = findIndicatorY(instrumentation, area) ?: checkNotNull(indicatorY)
                    instrumentation.uiAutomation
                        .executeShellCommand("input swipe $x $startY $x ${startY - distance} 600")
                        .close()
                    SystemClock.sleep(1200)
                    reachedNextPage = magentaVisible(instrumentation, area)
                }
                assertTrue("drags starting on the loading indicator must scroll past the loading page", reachedNextPage)
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    private fun waitForIndicator(
        instrumentation: android.app.Instrumentation,
        bounds: AtomicReference<Rect>,
    ): Int? {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val area = bounds.get()
            if (area != null) findIndicatorY(instrumentation, area)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    /** Vertical center of the indicator's tinted pixels along the center column band, if any. */
    private fun findIndicatorY(instrumentation: android.app.Instrumentation, area: Rect): Int? {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val cx = area.center.x.toInt()
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in area.top.toInt() until area.bottom.toInt() step 2) {
            for (x in (cx - 80) until (cx + 80) step 2) {
                val p = shot.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // The indicator is tinted with the theme primary over the black placeholder:
                // bluish, and unlike the cyan/green/magenta pages never saturated.
                if (max(r, max(g, b)) in 40..230 && b > g + 30) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        shot.recycle()
        return if (minY <= maxY) (minY + maxY) / 2 else null
    }

    private fun magentaVisible(instrumentation: android.app.Instrumentation, area: Rect): Boolean {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return false
        val x = area.center.x.toInt()
        var found = false
        for (y in area.top.toInt() until area.bottom.toInt() step 8) {
            val p = shot.getPixel(x, y)
            if (Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) > 200) {
                found = true
                break
            }
        }
        shot.recycle()
        return found
    }
}
