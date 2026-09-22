package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/** A settled scene page must keep its authoritative bitmap for the frames after a turn. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedPostTurnFlickerTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun coverTurnDoesNotFlashTheSettledPage() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val fixtures = colors.mapIndexed { index, color ->
            Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(color)
                File(context.cacheDir, "scene-post-turn-$index.png").also { file ->
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        val pages = fixtures.mapIndexed { index, file ->
            ReaderPage(index.toLong(), file.absolutePath, null, null, 1L, index, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fixtures[page.index])))
        }
        val activePage = AtomicLong(Long.MIN_VALUE)
        val imageLoader = ImageLoader(context)

        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                var width = 0
                var height = 0
                scenario.onActivity { activity ->
                    width = activity.window.decorView.width
                    height = activity.window.decorView.height
                    activity.setContent { Host(pages, pipeline, imageLoader, activePage) }
                }
                awaitPage(activePage, pages[0].readerKey)
                repeat(3) { turn ->
                    swipe(instrumentation, width, height)
                    awaitPage(activePage, pages[turn + 1].readerKey)
                    val expected = colors[turn + 1]
                    val samples = mutableListOf<Int>()
                    repeat(24) {
                        SystemClock.sleep(16)
                        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            samples += screenshot.getPixel(width / 2, height / 2)
                        } finally {
                            screenshot.recycle()
                        }
                    }
                    assertTrue(
                        "page ${turn + 1} flashed after the turn: samples=${samples.map(::hex)}",
                        samples.all { distance(it, expected) < 90 },
                    )
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    @Composable
    private fun Host(
        pages: List<ReaderPage>,
        pipeline: ComposeReaderImagePipeline,
        imageLoader: ImageLoader,
        activePage: AtomicLong,
    ) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                ComposeScenePagedReader(
                    pages = pages,
                    initialPage = 0,
                    readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                    imageLoader = imageLoader,
                    imagePipeline = pipeline,
                    onPagesChanged = { _, _, active -> activePage.set(active) },
                    zoomMode = ZoomMode.FIT_CENTER,
                    isAnimationEnabled = true,
                    pageAnimation = ReaderAnimation.ADVANCED,
                    readerBackground = ReaderBackground.BLACK,
                )
            }
        }
    }

    private fun swipe(
        instrumentation: android.app.Instrumentation,
        width: Int,
        height: Int,
    ) {
        val y = height * 0.5f
        val downAt = SystemClock.uptimeMillis()
        for (step in 0..8) {
            val now = downAt + step * 20L
            SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                8 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(
                downAt,
                now,
                action,
                width * (0.8f - 0.075f * step),
                y,
                0,
            )
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun awaitPage(activePage: AtomicLong, pageId: Long) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline && activePage.get() != pageId) {
            SystemClock.sleep(25)
        }
        assertTrue("reader did not settle on page $pageId", activePage.get() == pageId)
    }

    private fun distance(pixel: Int, expected: Int): Int {
        val dr = Color.red(pixel) - Color.red(expected)
        val dg = Color.green(pixel) - Color.green(expected)
        val db = Color.blue(pixel) - Color.blue(expected)
        return dr * dr + dg * dg + db * db
    }

    private fun hex(pixel: Int): String = Integer.toHexString(pixel)
}
