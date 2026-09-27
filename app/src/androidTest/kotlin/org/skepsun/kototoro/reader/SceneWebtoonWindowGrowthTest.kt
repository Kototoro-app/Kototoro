package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

/**
 * Issue #545: an adjacent chapter published while the webtoon host is still waiting for its first
 * full viewport must still become reachable.
 *
 * The scene is built as soon as the width is known, but the launch position is applied only once
 * the height is known too. A window expansion landing in between used to be skipped by the page
 * update and never replayed, so the reader stayed clamped at the edge of the launch chapter.
 * Holding the viewport height at zero while the chapter is appended makes that gap deterministic.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneWebtoonWindowGrowthTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val activePage = AtomicLong(Long.MIN_VALUE)
    private val reportCount = AtomicInteger(0)
    private val viewportWidthPx = AtomicInteger(0)

    private var pages by mutableStateOf(emptyList<ReaderPage>())
    private var viewportHeightDp by mutableIntStateOf(0)
    private var requestedPage by mutableStateOf<Int?>(null)

    @Test
    fun nextChapterAppendedBeforeTheFirstFullViewportIsReachable() {
        hiltRule.inject()
        val fixture = createFixture()
        val launchChapter = chapter(chapterId = 1L, fixture = fixture)
        val nextChapter = chapter(chapterId = 2L, fixture = fixture)
        pages = launchChapter
        val imageLoader = ImageLoader(context)
        val scenario = ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java)
                .putExtra("scene_recovery", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            scenario.onActivity { activity ->
                activity.setContent { HostContent(imageLoader) }
            }
            // The scene exists once the width is measured; the launch position is still pending.
            assertTrue("reader width was never measured", awaitTrue(10_000) { viewportWidthPx.get() > 0 })
            SystemClock.sleep(300)

            pages = launchChapter + nextChapter
            SystemClock.sleep(300)
            viewportHeightDp = 600
            assertTrue(
                "reader never reported a page after the viewport opened",
                awaitTrue(10_000) { reportCount.get() > 0 },
            )

            val target = pages.indexOfFirst { it.chapterId == 2L && it.index == 1 }
            requestedPage = target
            assertTrue(
                "requested page $target of the appended chapter was never reached; " +
                    "active page stayed at index ${activeIndex()}",
                awaitTrue(10_000) { activeIndex() == target },
            )
        } finally {
            imageLoader.shutdown()
            val closer = Thread { runCatching { scenario.close() } }
            closer.isDaemon = true
            closer.start()
            closer.join(5_000)
        }
    }

    /**
     * Issue #545, second path: once enough pages decode, the chapter-average ratio relays out the
     * still-loading placeholders. That relayout ran from the asset callback registered with the
     * launch window, so it rebuilt the scene from the launch chapter alone: a chapter prepended
     * meanwhile vanished and the anchor compensation pinned the reader to the launch chapter's top.
     */
    @Test
    fun ratioRelayoutAfterAPrependKeepsThePreviousChapter() {
        hiltRule.inject()
        val fixture = createFixture()
        val previousChapter = chapter(chapterId = 1L, fixture = fixture)
        val launchChapter = chapter(chapterId = 2L, fixture = fixture)
        decodeGate.value = false
        pages = launchChapter
        viewportHeightDp = 600
        val imageLoader = ImageLoader(context)
        val scenario = ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java)
                .putExtra("scene_recovery", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            scenario.onActivity { activity ->
                activity.setContent { HostContent(imageLoader) }
            }
            assertTrue("reader never reported a page", awaitTrue(10_000) { reportCount.get() > 0 })

            pages = previousChapter + launchChapter
            SystemClock.sleep(500)
            // Decoding now converges the chapter ratio and triggers the placeholder relayout.
            decodeGate.value = true
            SystemClock.sleep(1_500)

            val target = pages.indexOfFirst { it.chapterId == 1L && it.index == 1 }
            requestedPage = target
            assertTrue(
                "requested page $target of the prepended chapter was never reached after the ratio " +
                    "relayout; active page stayed at index ${activeIndex()}",
                awaitTrue(10_000) { activeIndex() == target },
            )
        } finally {
            imageLoader.shutdown()
            val closer = Thread { runCatching { scenario.close() } }
            closer.isDaemon = true
            closer.start()
            closer.join(5_000)
        }
    }

    @androidx.compose.runtime.Composable
    private fun HostContent(imageLoader: ImageLoader) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(viewportHeightDp.dp)
                    .onSizeChanged { size -> viewportWidthPx.set(size.width) },
            ) {
                ComposeSceneWebtoonReader(
                    pages = pages,
                    initialPage = 0,
                    initialScroll = 0,
                    imageLoader = imageLoader,
                    imagePipeline = pipeline,
                    onPagesChanged = { _, _, active ->
                        activePage.set(active)
                        reportCount.incrementAndGet()
                    },
                    onInternalScrollChanged = { _, _ -> },
                    requestedPage = requestedPage,
                    readerBackgroundColor = Color.BLACK,
                )
            }
        }
    }

    /** Holds every page in the loading state until released, so decode timing is under test control. */
    private val decodeGate = MutableStateFlow(true)

    private val pipeline = object : ComposeReaderImagePipeline {
        override fun observe(page: ReaderPage, force: Boolean) = decodeGate.map { released ->
            if (released) {
                ComposeReaderImageState.OriginalReady(Uri.parse(page.url))
            } else {
                ComposeReaderImageState.LoadingOriginal
            }
        }
    }

    private fun activeIndex(): Int = pages.indexOfFirst { it.readerKey == activePage.get() }

    private fun chapter(chapterId: Long, fixture: File): List<ReaderPage> = List(3) { index ->
        ReaderPage(chapterId * 100 + index, fixture.toURI().toString(), null, null, chapterId, index, LocalMangaSource)
    }

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun createFixture(): File {
        val bitmap = Bitmap.createBitmap(640, 1800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val file = File(context.cacheDir, "scene-webtoon-window-growth.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
