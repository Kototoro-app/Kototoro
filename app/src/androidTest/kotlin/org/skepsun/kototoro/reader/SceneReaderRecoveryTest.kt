package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import coil3.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneWebtoonReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real scene, image adapter, Coil decoding and retry button without a network source. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneReaderRecoveryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun failedPageCanRetryAndDrawLocalImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        hiltRule.inject()
        val fixture = File(context.cacheDir, "scene-reader-recovery.png")
        val bitmap = Bitmap.createBitmap(640, 1280, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(0f, 640f, 640f, 1280f, Paint().apply { color = Color.BLUE })
        }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val attempts = AtomicInteger()
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) = flow {
                attempts.incrementAndGet()
                emit(ComposeReaderImageState.LoadingOriginal)
                delay(300)
                emit(
                    if (force) ComposeReaderImageState.OriginalReady(Uri.fromFile(fixture))
                    else ComposeReaderImageState.Failed(null, SocketTimeoutException("Scene fixture timeout")),
                )
            }
        }
        val page = ReaderPage(1, fixture.toURI().toString(), null, null, 1, 0, LocalMangaSource)
        val imageLoader = ImageLoader(context)
        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            ComposeSceneWebtoonReader(
                                pages = listOf(page), initialPage = 0, initialScroll = 0,
                                imageLoader = imageLoader, imagePipeline = pipeline,
                                onPagesChanged = { _, _, _ -> }, onInternalScrollChanged = { _, _ -> },
                            )
                        }
                    }
                }
                val automation = instrumentation.uiAutomation
                fun findRetry() = automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(context.getString(R.string.retry))?.firstOrNull()
                waitUntil { findRetry() != null }
                assertTrue("The failed request must stay settled", attempts.get() == 1)
                assertTrue("Retry must be actionable", findRetry()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil {
                    val screenshot = automation.takeScreenshot() ?: return@waitUntil false
                    var red = 0
                    var blue = 0
                    for (y in screenshot.height / 10 until screenshot.height * 9 / 10 step 30) {
                        val color = screenshot.getPixel(screenshot.width / 2, y)
                        if (Color.red(color) > 220 && Color.blue(color) < 40) red++
                        if (Color.blue(color) > 220 && Color.red(color) < 40) blue++
                    }
                    val drawn = red > 5 && blue > 5
                    if (drawn) File(context.cacheDir, "scene-reader-recovery-result.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                    drawn
                }
                assertTrue("Retry should issue exactly one new request", attempts.get() == 2)
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Scene reader did not reach the expected visible state")
    }
}
