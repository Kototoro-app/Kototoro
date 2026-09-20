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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.skepsun.kototoro.R
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

/**
 * Drives the Scene paged host on a real activity for the §5.1 matrix.
 *
 * Observes two independent channels and never conflates them:
 * - the reader's own state, through `onPagesChanged` (the plan's source of truth);
 * - what is announced to accessibility, through the viewport's content description.
 *
 * Stability rules learned the hard way on MIUI (each one hung a whole run):
 * - no `Instrumentation.waitForIdleSync()`: the reader can keep redrawing, so the queue never idles;
 * - no accessibility-root query inside a polling loop;
 * - `ActivityScenario.close()` is bounded, and the viewport size is captured once per launch
 *   instead of asking the activity (which blocks on the main thread) before every gesture.
 */
internal class ScenePagedTransitionHarness(
    private val pageCount: Int = 4,
    private val isAnimationEnabled: Boolean = true,
    private val pageAnimation: ReaderAnimation = ReaderAnimation.DEFAULT,
    private val readingDirection: SceneReadingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
    private val initialPage: Int = 0,
    private val zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
) : AutoCloseable {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val activePage = AtomicLong(Long.MIN_VALUE)
    private val reportCount = AtomicInteger(0)
    private val widthPx = AtomicInteger(0)
    private val heightPx = AtomicInteger(0)
    private lateinit var scenario: ActivityScenario<IdleProbeActivity>
    private lateinit var imageLoader: ImageLoader
    private lateinit var fixture: File

    lateinit var pages: List<ReaderPage>
        private set

    private val pipeline: ComposeReaderImagePipeline by lazy {
        object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fixture)))
        }
    }

    fun launch() {
        fixture = createFixture()
        pages = (0 until pageCount).map { index ->
            ReaderPage(index.toLong(), fixture.toURI().toString(), null, null, 1, index, LocalMangaSource)
        }
        imageLoader = ImageLoader(context)
        repeat(3) { attempt ->
            if (attempt > 0) SystemClock.sleep(1_500)
            scenario = ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            applyContent(initialPage)
            if (awaitReport(20_000)) return
            android.util.Log.w("SceneMatrix", "launch attempt ${attempt + 1} reported no state")
            close()
        }
        assertTrue("reader never reported its page state after three launches", false)
    }

    private fun applyContent(page: Int) {
        scenario.onActivity { activity ->
            widthPx.set(activity.window.decorView.width)
            heightPx.set(activity.window.decorView.height)
            activity.setContent {
                MaterialTheme {
                    ComposeScenePagedReader(
                        pages = pages,
                        initialPage = page,
                        readingDirection = readingDirection,
                        imageLoader = imageLoader,
                        imagePipeline = pipeline,
                        onPagesChanged = { _, _, active ->
                            activePage.set(active)
                            reportCount.incrementAndGet()
                        },
                        zoomMode = zoomMode,
                        isAnimationEnabled = isAnimationEnabled,
                        pageAnimation = pageAnimation,
                        readerBackground = ReaderBackground.BLACK,
                    )
                }
            }
        }
    }

    private fun awaitReport(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (reportCount.get() > 0 && activePageIndex() >= 0) return true
            SystemClock.sleep(50)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------------------------------

    fun activePageIndex(): Int = pages.indexOfFirst { it.readerKey == activePage.get() }

    fun awaitActivePage(index: Int, what: String, timeoutMs: Long = 15_000) {
        awaitTrue("$what: reader state stayed on page index ${activePageIndex()} (expected $index)",
            timeoutMs) { activePageIndex() == index }
    }

    fun awaitActivePageWithin(indexes: Set<Int>, what: String, timeoutMs: Long = 15_000) {
        awaitTrue("$what: reader state stayed on page index ${activePageIndex()} (expected one of $indexes)",
            timeoutMs) { activePageIndex() in indexes }
    }

    /** 1-based page number announced by the viewport, or null when nothing is announced. */
    fun announcedPageNumber(): Int? {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return null
        for (page in 1..pages.size) {
            val expected = context.getString(R.string.reader_a11y_page_position, page, pages.size)
            if (findByDescription(root, expected) != null) return page
        }
        return null
    }

    /** Lets any late animation finish so an assertion cannot pass on a stale frame. */
    fun holdSettled(millis: Long) = SystemClock.sleep(millis)

    // ---------------------------------------------------------------------------------------------
    // Input (one gesture through screen-width fractions; no activity calls in the loop)
    // ---------------------------------------------------------------------------------------------

    private fun gesture(waypoints: List<Float>, stepsPerSegment: Int = 6, stepMs: Long = 20) {
        val width = if (widthPx.get() > 0) widthPx.get().toFloat() else 1080f
        val y = (if (heightPx.get() > 0) heightPx.get() else 1920) / 2f
        val path = mutableListOf<Float>()
        for (index in 0 until waypoints.size - 1) {
            val from = width * waypoints[index]
            val to = width * waypoints[index + 1]
            for (step in 0 until stepsPerSegment) path += from + (to - from) * step / stepsPerSegment
        }
        path += width * waypoints.last()

        val start = SystemClock.uptimeMillis()
        path.forEachIndexed { index, x ->
            val now = start + index * stepMs
            SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                path.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, now, action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        SystemClock.sleep(120)
    }

    fun swipeForward() = gesture(listOf(0.8f, 0.2f))

    fun swipeBackward() = gesture(listOf(0.2f, 0.8f))

    /** Past the turn threshold, back to the origin, released without a fling. */
    fun cancelledDrag() = gesture(listOf(0.8f, 0.45f, 0.79f, 0.8f, 0.8f), stepsPerSegment = 6, stepMs = 25)

    /** Starts the opposite turn while the first one is still animating. */
    fun interruptedTurn() {
        swipeForward()
        SystemClock.sleep(90)
        swipeBackward()
    }

    /** A press/release that turns nothing; used to force one more frame. */
    fun tapCenter() {
        val width = if (widthPx.get() > 0) widthPx.get().toFloat() else 1080f
        val y = (if (heightPx.get() > 0) heightPx.get() else 1920) / 2f
        val x = width / 2f
        val down = SystemClock.uptimeMillis()
        val first = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        instrumentation.sendPointerSync(first)
        first.recycle()
        SystemClock.sleep(40)
        val up = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0)
        instrumentation.sendPointerSync(up)
        up.recycle()
        SystemClock.sleep(120)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun awaitTrue(message: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, false)
    }

    private fun findByDescription(node: AccessibilityNodeInfo?, exact: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription == exact) return node
        for (index in 0 until node.childCount) {
            findByDescription(node.getChild(index), exact)?.let { return it }
        }
        return null
    }

    private fun createFixture(): File {
        val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(0f, 480f, 640f, 960f, Paint().apply { color = Color.BLUE })
        }
        val file = File(context.cacheDir, "scene-paged-harness.png")
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
