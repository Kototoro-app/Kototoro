package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
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
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * The promotion checklist's "no reproducible blank or flicker", turned into an assertion.
 *
 * The plan lists blank/flicker as a promotion condition but never gave it a check, so the condition
 * rested on nobody having noticed one. What a reader must not do is show its background where page
 * content belongs — on arrival, while a turn animates, or afterwards — so this samples the viewport
 * continuously across a page turn and fails on any sample that contains no page colour at all.
 *
 * The pages are flat, distinct colours, which makes "content" a cheap and unambiguous test: any
 * sample whose pixels are all background is a blank frame, whatever caused it.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedBlankFrameTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun aPageTurnNeverShowsABlankViewport() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val fixtures = colors.mapIndexed { index, color -> createFixture(context, index, color) }
        val pages = fixtures.mapIndexed { index, file ->
            ReaderPage(index.toLong(), file.absolutePath, null, null, 1, index, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(
                    ComposeReaderImageState.OriginalReady(
                        Uri.fromFile(fixtures[page.index.coerceIn(fixtures.indices)]),
                    ),
                )
        }
        val imageLoader = ImageLoader(context)
        val blankSamples = mutableListOf<String>()
        var firstContentAt = -1L

        fun isBlankSample(instrumentation: android.app.Instrumentation, colors: List<Int>): Boolean {
            val shot = instrumentation.uiAutomation.takeScreenshot() ?: return false
            try {
                // A sparse grid rather than every pixel: a whole-viewport blank is not a subtle event.
                for (x in 0 until shot.width step 64) {
                    for (y in 0 until shot.height step 64) {
                        if (nearestFixtureDistance(shot.getPixel(x, y), colors) < 60) return false
                    }
                }
                return true
            } finally {
                shot.recycle()
            }
        }

        fun sample(label: String) {
            if (isBlankSample(instrumentation, colors)) blankSamples.add(label)
        }

        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { Host(pages, pipeline, imageLoader) }
                }

                // From the first moment: arriving at a page must not show the background either.
                // The first samples are also timed, because "blank for one frame" and "blank until
                // the reader settles" are different findings.
                val arrivalStart = SystemClock.uptimeMillis()
                var arrivalBlank = 0
                repeat(12) { step ->
                    val elapsed = SystemClock.uptimeMillis() - arrivalStart
                    val blank = isBlankSample(instrumentation, colors)
                    if (!blank && firstContentAt < 0) firstContentAt = elapsed
                    if (blank) arrivalBlank++
                    SystemClock.sleep(50)
                }
                android.util.Log.i(
                    "SceneDiag",
                    "blank-frame arrival: first content at ${firstContentAt}ms, blank samples=$arrivalBlank/12",
                )

                // Then across two animated turns, sampled well inside the animation window so a
                // frame that dropped its content mid-slide cannot slip between samples.
                repeat(2) { turn ->
                    val width = 1280
                    val midY = 1386
                    val downAt = SystemClock.uptimeMillis()
                    val steps = 10
                    for (step in 0..steps) {
                        val eventAt = downAt + step * 30L
                        SystemClock.sleep((eventAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
                        val x = width * 0.85f - (width * 0.65f) * step / steps
                        val action = when (step) {
                            0 -> android.view.MotionEvent.ACTION_DOWN
                            steps -> android.view.MotionEvent.ACTION_UP
                            else -> android.view.MotionEvent.ACTION_MOVE
                        }
                        val event = android.view.MotionEvent.obtain(downAt, eventAt, action, x, midY.toFloat(), 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                        sample("turn$turn+${step * 30}ms")
                    }
                    SystemClock.sleep(500)
                    sample("turn$turn-settled")
                }
            }

            assertTrue(
                "the viewport showed no page content in ${blankSamples.size} sample(s) while turning: " +
                    blankSamples.take(12).joinToString(", ") +
                    (if (blankSamples.size > 12) " …" else ""),
                blankSamples.isEmpty(),
            )
            // The arrival blank above is measured, not asserted away: the host keeps its surface at
            // alpha 0 until the position is anchored, so the first frames are the reader background.
            // Pinning the bound keeps "a couple of frames" from silently becoming "until something
            // else happens", which is the failure mode that would make a cold start look broken.
            assertTrue(
                "the reader showed no page content for ${firstContentAt}ms after composing",
                firstContentAt in 0..ARRIVAL_CONTENT_BUDGET_MS,
            )
        } finally {
            imageLoader.shutdown()
        }
    }

    @Composable
    private fun Host(
        pages: List<ReaderPage>,
        pipeline: ComposeReaderImagePipeline,
        imageLoader: ImageLoader,
    ) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                ComposeScenePagedReader(
                    pages = pages,
                    initialPage = 0,
                    readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                    imageLoader = imageLoader,
                    imagePipeline = pipeline,
                    onPagesChanged = { _, _, _ -> },
                    zoomMode = ZoomMode.FIT_CENTER,
                    // The animation is on: a blank frame is most likely to appear while one runs.
                    isAnimationEnabled = true,
                    pageAnimation = ReaderAnimation.DEFAULT,
                    readerBackground = org.skepsun.kototoro.core.prefs.ReaderBackground.BLACK,
                )
            }
        }
    }

    private fun nearestFixtureDistance(pixel: Int, colors: List<Int>): Int =
        colors.minOf { candidate ->
            val dr = Color.red(pixel) - Color.red(candidate)
            val dg = Color.green(pixel) - Color.green(candidate)
            val db = Color.blue(pixel) - Color.blue(candidate)
            dr * dr + dg * dg + db * db
        }

    private fun createFixture(context: android.content.Context, index: Int, color: Int): File {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color, android.graphics.PorterDuff.Mode.SRC)
        val file = File(context.cacheDir, "scene-blank-frame-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        /**
         * Measured at 155ms on M332BF - 17 for a three-page local fixture. The budget is a bound, not
         * the measurement: it exists so a cold start that stops painting entirely fails here instead
         * of looking like a slightly slower launch.
         */
        const val ARRIVAL_CONTENT_BUDGET_MS = 2_000L
    }
}
