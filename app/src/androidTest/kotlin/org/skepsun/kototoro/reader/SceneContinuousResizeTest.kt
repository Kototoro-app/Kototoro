package org.skepsun.kototoro.reader

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneHorizontalReader
import org.skepsun.kototoro.reader.ui.compose.ComposeSceneWebtoonReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * Improvement plan section 5.1, rows "lifecycle" and "continuous": the continuous hosts must keep
 * the reading position across a viewport resize, the same requirement the paged host is held to in
 * [ScenePagedViewportResizeTest].
 *
 * These two hosts were believed safe on inspection alone — their scroll state is keyed on the scene
 * object rather than on the viewport size, so the offset survives while the scene is rebuilt — and a
 * belief about a lifecycle path is exactly the kind that has already been wrong twice in this plan.
 * The cases below turn it into device evidence.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneContinuousResizeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * Device evidence that the continuous hosts hold the reading position across a viewport resize.
     *
     * The webtoon cell is **unstable**: with this same APK and this same assertion it has passed and
     * failed repeatedly, and it once failed three runs in a row and then passed three runs in a row.
     * The failures all read `WEBTOON page after the resize expected:<2> but was:<0>`, i.e. the
     * position is on page 2 before the resize and on the chapter's first page after it — but a
     * flaky cell is not evidence of a defect until the flakiness itself is attributed, so this is
     * recorded as an open question rather than asserted green. The horizontal cell and the
     * first-page control are stable (6/6 and 5/5 runs).
     */
    @Test
    fun webtoonKeepsThePageAcrossAResize() = withHost(continuousHost = ContinuousHost.WEBTOON)

    @Test
    fun horizontalKeepsThePageAcrossAResize() = withHost(continuousHost = ContinuousHost.HORIZONTAL)
    /** The control: a host that never turns a page cannot show a lost position. */
    @Test
    fun webtoonOnTheFirstPageStaysOnTheFirstPage() {
        hiltRule.inject()
        ContinuousHarness(ContinuousHost.WEBTOON).use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.resizeViewport(0.7f)
            host.awaitActivePage(0, "resize on page 1")
        }
    }

    private fun withHost(continuousHost: ContinuousHost) {
        hiltRule.inject()
        // Launch the host already positioned inside a later page: the position under test is the
        // one the reader holds, and how it got there (a jump, a fling or a restore) is a separate
        // question from whether a viewport change preserves it.
        ContinuousHarness(continuousHost, initialPage = 2, initialScroll = 40).use { host ->
            host.launch()
            host.awaitActivePage(2, "launched position")
            host.holdSettled(1_500)
            val scrollBefore = host.currentScroll()
            android.util.Log.i(
                "SceneMatrix",
                "continuous before resize $continuousHost activePage=${host.activePageIndex()} " +
                    "scroll=$scrollBefore viewport=${host.readerViewportSize()}",
            )

            val (widthBefore, heightBefore) = host.readerViewportSize()
            host.resizeViewport(0.7f)
            host.awaitViewportResized(widthBefore, heightBefore, "$continuousHost viewport")
            host.holdSettled(1_000)
            assertEquals(
                "$continuousHost page after the resize",
                2,
                host.activePageIndex(),
            )
            assertTrue(
                "$continuousHost offset after the resize: ${host.currentScroll()} (was $scrollBefore)",
                host.currentScroll() == scrollBefore,
            )
        }
    }
}

internal enum class ContinuousHost { WEBTOON, HORIZONTAL }

/**
 * Minimal driver for the two continuous hosts: real activity, real viewport, real pointer input, and
 * a bounded resize that reproduces what split screen and foldable posture changes do to them.
 */
internal class ContinuousHarness(
    private val host: ContinuousHost,
    private val pageCount: Int = 4,
    private val initialPage: Int = 0,
    private val initialScroll: Int = 0,
) : AutoCloseable {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val activePage = AtomicLong(Long.MIN_VALUE)
    private val reportCount = AtomicInteger(0)
    private val scrollValue = AtomicInteger(0)
    private val scrollPage = AtomicLong(Long.MIN_VALUE)
    private val windowWidthPx = AtomicInteger(0)
    private val windowHeightPx = AtomicInteger(0)
    private val readerViewportWidthPx = AtomicInteger(0)
    private val readerViewportHeightPx = AtomicInteger(0)
    private var viewportScale by mutableFloatStateOf(1f)
    private var requestedPage by mutableStateOf<Int?>(null)
    private lateinit var scenario: ActivityScenario<IdleProbeActivity>
    private lateinit var imageLoader: ImageLoader

    private lateinit var pages: List<ReaderPage>

    private val pipeline: ComposeReaderImagePipeline by lazy {
        object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.parse(page.url)))
        }
    }

    fun launch() {
        val fixture = createFixture()
        pages = (0 until pageCount).map { index ->
            ReaderPage(index.toLong(), fixture.toURI().toString(), null, null, 1, index, LocalMangaSource)
        }
        imageLoader = ImageLoader(context)
        scenario = ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java)
                .putExtra("scene_recovery", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        applyContent()
        assertTrue(
            "continuous host never reported a page",
            awaitTrue(20_000) { reportCount.get() > 0 && activePageIndex() >= 0 },
        )
    }

    private fun applyContent() {
        scenario.onActivity { activity ->
            windowWidthPx.set(activity.window.decorView.width)
            windowHeightPx.set(activity.window.decorView.height)
            activity.setContent { HostContent(viewportScale) }
        }
    }

    @Composable
    private fun HostContent(scale: Float) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight((windowHeightPx.get() * scale).dp)
                    .onSizeChanged { size ->
                        readerViewportWidthPx.set(size.width)
                        readerViewportHeightPx.set(size.height)
                        android.util.Log.i("SceneMatrix", "continuous box size=$size scale=$scale")
                    },
            ) {
                val onPagesChanged: (Long, Long, Long) -> Unit = { _, _, active ->
                    activePage.set(active)
                    reportCount.incrementAndGet()
                }
                val onInternalScroll: (ReaderPage, Int) -> Unit = { page, scroll ->
                    scrollPage.set(page.readerKey)
                    scrollValue.set(scroll)
                }
                when (host) {
                    ContinuousHost.WEBTOON -> ComposeSceneWebtoonReader(
                        pages = pages,
                        initialPage = initialPage,
                        initialScroll = initialScroll,
                        imageLoader = imageLoader,
                        imagePipeline = pipeline,
                        onPagesChanged = onPagesChanged,
                        onInternalScrollChanged = onInternalScroll,
                        requestedPage = requestedPage,
                        readerBackgroundColor = Color.BLACK,
                    )

                    ContinuousHost.HORIZONTAL -> ComposeSceneHorizontalReader(
                        pages = pages,
                        initialPage = initialPage,
                        initialScroll = initialScroll,
                        imageLoader = imageLoader,
                        imagePipeline = pipeline,
                        readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                        onPagesChanged = onPagesChanged,
                        onInternalScrollChanged = onInternalScroll,
                        requestedPage = requestedPage,
                        readerBackgroundColor = Color.BLACK,
                    )
                }
            }
        }
    }

    /** A real resize of the reader's own viewport: the activity and the composition both survive. */
    fun resizeViewport(scale: Float): Int {
        val before = activePageIndex()
        val reportsBefore = reportCount.get()
        viewportScale = scale
        SystemClock.sleep(800)
        val reports = reportCount.get() - reportsBefore
        android.util.Log.i(
            "SceneMatrix",
            "continuous resize $host scale=$scale activePage $before -> ${activePageIndex()} reports=$reports",
        )
        return reports
    }

    fun activePageIndex(): Int = pages.indexOfFirst { it.readerKey == activePage.get() }

    /** The reader's own reported offset inside the current page, in page pixels. */
    fun currentScroll(): Int = scrollValue.get()

    fun awaitActivePage(index: Int, what: String, timeoutMs: Long = 15_000) {
        assertTrue(
            "$what: $host state stayed on page index ${activePageIndex()} (expected $index)",
            awaitTrue(timeoutMs) { activePageIndex() == index },
        )
    }

    /**
     * Waits until the reader has actually been laid out at the new size. A host that does not
     * re-report on every frame would otherwise be asserted against its pre-resize value, which makes
     * "the position held" a race the assertion can lose or win by accident.
     */
    fun awaitViewportResized(widthBefore: Int, heightBefore: Int, what: String, timeoutMs: Long = 15_000) {
        assertTrue(
            "$what: reader viewport stayed ${readerViewportWidthPx.get()}x${readerViewportHeightPx.get()} " +
                "(was ${widthBefore}x$heightBefore)",
            awaitTrue(timeoutMs) {
                readerViewportWidthPx.get() != widthBefore || readerViewportHeightPx.get() != heightBefore
            },
        )
    }

    fun readerViewportSize(): Pair<Int, Int> = readerViewportWidthPx.get() to readerViewportHeightPx.get()

    fun reportCount(): Int = reportCount.get()

    fun holdSettled(millis: Long) = SystemClock.sleep(millis)

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun createFixture(): File {
        // A page fitted to the viewport width is displayed at roughly double its own width, so a
        // 640x600 page occupies about 1200 screen pixels. One full-viewport drag therefore crosses
        // it and lands inside the next page with a measurable offset; a taller page would swallow
        // the whole swipe and never turn.
        val bitmap = Bitmap.createBitmap(640, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(0f, 300f, 640f, 600f, Paint().apply { color = Color.BLUE })
        }
        val file = File(context.cacheDir, "scene-continuous-harness-${host.name}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    override fun close() {
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        if (::scenario.isInitialized) {
            val closer = Thread { runCatching { scenario.close() } }
            closer.isDaemon = true
            closer.start()
            closer.join(5_000)
        }
    }
}
