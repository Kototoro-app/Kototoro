package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/** Checks the revealed page, since a whole-viewport blank check still passes with an opaque outgoing page. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedCoverCacheTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun cachedNeighbourStaysVisibleAcrossCancelledCoverTurns() =
        assertCachedNeighbourStaysVisible(ReaderAnimation.ADVANCED)

    @Test
    fun switchingToCoverPreparesCachedNeighbours() =
        assertCachedNeighbourStaysVisible(ReaderAnimation.DEFAULT)

    private fun assertCachedNeighbourStaysVisible(initialAnimation: ReaderAnimation) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtures = listOf(Color.RED, Color.GREEN).mapIndexed { index, color ->
            val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            File(context.cacheDir, "scene-cover-cache-$index.png").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val pages = fixtures.mapIndexed { index, file ->
            ReaderPage(index.toLong(), file.absolutePath, null, null, 1, index, LocalMangaSource)
        }
        val readyStates = pages.associate { page ->
            page.readerKey to ComposeReaderImageState.OriginalReady(Uri.fromFile(fixtures[page.index]))
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = flowOf(readyStates.getValue(page.readerKey))
            override fun cachedState(pageKey: Long) = readyStates[pageKey]
        }
        val activePage = AtomicLong(Long.MIN_VALUE)
        var animation by mutableStateOf(initialAnimation)
        // Source files are already cached; make presentation acquisition slow enough to expose
        // a decode started at touch time, independently of device speed and Coil's memory cache.
        val imageLoader = ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .components {
                add(Interceptor { chain ->
                    delay(750)
                    chain.proceed()
                })
            }
            .build()

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
                    activity.setContent {
                        MaterialTheme {
                            ComposeScenePagedReader(
                                pages = pages,
                                initialPage = 0,
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                pageAnimation = animation,
                                isPreloadReductionEnabled = true,
                                readerBackground = ReaderBackground.BLACK,
                            )
                        }
                    }
                }
                SystemClock.sleep(2_500)
                assertEquals("reader must start on the first page", pages[0].readerKey, activePage.get())
                if (initialAnimation != ReaderAnimation.ADVANCED) {
                    scenario.onActivity { animation = ReaderAnimation.ADVANCED }
                    SystemClock.sleep(2_500)
                }
                repeat(2) { attempt ->
                    val downAt = SystemClock.uptimeMillis()
                    fun touch(action: Int, x: Float) {
                        val event = MotionEvent.obtain(
                            downAt, SystemClock.uptimeMillis(), action, x, height * 0.5f, 0,
                        )
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    touch(MotionEvent.ACTION_DOWN, width * 0.8f)
                    try {
                        SystemClock.sleep(40)
                        touch(MotionEvent.ACTION_MOVE, width * 0.7f)
                        SystemClock.sleep(40)
                        touch(MotionEvent.ACTION_MOVE, width * 0.6f)
                        SystemClock.sleep(80)
                        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            File(context.cacheDir, "scene-cover-cache-turn-$attempt.png").outputStream().use {
                                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            val pixel = shot.getPixel((width * 0.95f).toInt(), height / 2)
                            assertTrue(
                                "cached incoming page flashed a placeholder on turn $attempt: " +
                                    "pixel=${Integer.toHexString(pixel)}",
                                Color.green(pixel) > 220 && Color.red(pixel) < 30 && Color.blue(pixel) < 30,
                            )
                        } finally {
                            shot.recycle()
                        }
                    } finally {
                        touch(MotionEvent.ACTION_MOVE, width * 0.8f)
                        SystemClock.sleep(200)
                        touch(MotionEvent.ACTION_UP, width * 0.8f)
                    }
                    // Cancellation used to downgrade the now-hidden neighbour back to SOURCE_READY.
                    SystemClock.sleep(1_000)
                    assertEquals("cancelled turn must return to the first page", pages[0].readerKey, activePage.get())
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }
}
