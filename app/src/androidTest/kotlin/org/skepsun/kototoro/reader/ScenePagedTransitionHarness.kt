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
    private val reportedLower = AtomicLong(Long.MIN_VALUE)
    private val reportedUpper = AtomicLong(Long.MIN_VALUE)
    private val reportCount = AtomicInteger(0)
    private val viewportWidthPx = AtomicInteger(0)
    private val viewportHeightPx = AtomicInteger(0)

    /**
     * What the reader itself last measured, fed from the same `onSizeChanged` the host uses. The
     * layout rectangle the harness asks for and the rectangle the reader gets are not the same
     * thing (window insets, the shell's own chrome), so the resize assertions read this, not the
     * harness's intent.
     */
    private val readerViewportWidthPx = AtomicInteger(0)
    private val readerViewportHeightPx = AtomicInteger(0)
    private var viewportScale by mutableFloatStateOf(1f)

    /**
     * The programmatic page request the host passes down. The real host uses it to re-anchor after a
     * layout rebuild (rotation, mode or double-page change) instead of relying on `initialPage`,
     * so a lifecycle case that never sets it is not exercising the path the app takes.
     */
    private var requestedPage by mutableStateOf<Int?>(null)
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
            viewportWidthPx.set(activity.window.decorView.width)
            viewportHeightPx.set(activity.window.decorView.height)
            activity.setContent {
                MaterialTheme {
                    SceneReader(page, viewportScale)
                }
            }
        }
    }

    @Composable
    private fun SceneReader(page: Int, scale: Float) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight((viewportHeightPx.get() * scale).dp)
                .onSizeChanged { size ->
                    readerViewportWidthPx.set(size.width)
                    readerViewportHeightPx.set(size.height)
                    android.util.Log.i("SceneMatrix", "scene box size=$size scale=$scale")
                },
        ) {
            ComposeScenePagedReader(
                pages = pages,
                initialPage = page,
                readingDirection = readingDirection,
                imageLoader = imageLoader,
                imagePipeline = pipeline,
                onPagesChanged = { lower, upper, active ->
                    reportedLower.set(lower)
                    reportedUpper.set(upper)
                    activePage.set(active)
                    reportCount.incrementAndGet()
                },
                requestedPage = requestedPage,
                zoomMode = zoomMode,
                isAnimationEnabled = isAnimationEnabled,
                pageAnimation = pageAnimation,
                readerBackground = ReaderBackground.BLACK,
            )
        }
    }

    /**
     * Requests a page the way the host does on a layout rebuild: through the `requestedPage` prop,
     * not by recomposing with a different launch page.
     */
    fun requestPage(index: Int) {
        awaitStateQuiet()
        requestedPage = index
        SystemClock.sleep(600)
    }

    /**
     * Changes the reader's viewport without recreating the activity or the composition, i.e. the
     * pure resize path (rotation with `configChanges`, split-screen, foldable posture change). Only
     * the size state is written, so the reader keeps its own state exactly as it does in the app;
     * `initialPage` stays at its launch value and must not be what carries the position.
     *
     * Returns how many state reports the reader produced while shrinking; a resize is only
     * meaningful evidence once the reader has re-reported at the new size.
     */
    fun resizeViewport(scale: Float): Int {
        awaitStateQuiet()
        val before = activePageIndex()
        val sizeBefore = readerViewportSize()
        val reportsBefore = reportCount.get()
        viewportScale = scale
        SystemClock.sleep(800)
        val reports = reportCount.get() - reportsBefore
        if (reports > 0) awaitStateQuiet()
        android.util.Log.i(
            "SceneMatrix",
            "resize scale=$scale viewport $sizeBefore -> ${readerViewportSize()} " +
                "activePage $before -> ${activePageIndex()} reports=$reports window=${reportedWindow()}",
        )
        return reports
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

    /**
     * The reader's own viewport size in pixels, as the host measured it last. This is the same
     * `onSizeChanged` the reader uses, so a resize is only evidence once this value has moved.
     */
    fun readerViewportSize(): Pair<Int, Int> = readerViewportWidthPx.get() to readerViewportHeightPx.get()

    /** The whole settled window the host last reported, as page indexes. */
    fun reportedWindow(): Triple<Int, Int, Int> = Triple(
        pages.indexOfFirst { it.readerKey == reportedLower.get() },
        pages.indexOfFirst { it.readerKey == reportedUpper.get() },
        activePageIndex(),
    )

    /** True while a page identity has never been reported, so assertions cannot read a sentinel. */
    fun hasReported(): Boolean = reportCount.get() > 0

    /** Lets any late animation finish so an assertion cannot pass on a stale frame. */
    fun holdSettled(millis: Long) = SystemClock.sleep(millis)

    // ---------------------------------------------------------------------------------------------
    // Input (one gesture through screen-width fractions; no activity calls in the loop)
    // ---------------------------------------------------------------------------------------------

    /** Centre of the reader's current (possibly resized) viewport. */
    private fun viewportCenter(): Pair<Float, Float> {
        val width = if (viewportWidthPx.get() > 0) viewportWidthPx.get().toFloat() else 1080f
        val windowHeight = if (viewportHeightPx.get() > 0) viewportHeightPx.get().toFloat() else 1920f
        return width / 2f to windowHeight * viewportScale / 2f
    }

    private fun gesture(waypoints: List<Float>, stepsPerSegment: Int = 6, stepMs: Long = 20) {
        awaitStateQuiet()
        val width = if (viewportWidthPx.get() > 0) viewportWidthPx.get().toFloat() else 1080f
        val y = viewportCenter().second
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

    /**
     * Injects the turn and, if the reader state did not move, injects again — returning how many
     * attempts it took. Retrying keeps a cell about the style's landing behaviour instead of about
     * injection luck; the attempt count is logged so reliability stays measurable separately.
     */
    fun swipeForwardCommitting(maxAttempts: Int = 3): Int =
        swipeCommitting("forward", maxAttempts) { swipeForward() }

    fun swipeBackwardCommitting(maxAttempts: Int = 3): Int =
        swipeCommitting("backward", maxAttempts) { swipeBackward() }

    private fun swipeCommitting(direction: String, maxAttempts: Int, swipe: () -> Unit): Int {
        val start = activePageIndex()
        repeat(maxAttempts) { attempt ->
            swipe()
            val deadline = SystemClock.uptimeMillis() + 4_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (activePageIndex() != start && activePageIndex() >= 0) {
                    if (attempt > 0) {
                        android.util.Log.w(
                            "SceneMatrix",
                            "injection needed ${attempt + 1} attempts to commit a $direction turn",
                        )
                    }
                    return attempt + 1
                }
                SystemClock.sleep(50)
            }
            android.util.Log.w("SceneMatrix", "$direction swipe attempt ${attempt + 1} did not commit")
        }
        return maxAttempts
    }

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
        val (x, y) = viewportCenter()
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

    /**
     * A gesture must not land while the reader is still reporting state (initial settle, a running
     * animation): an injected drag during that window can be absorbed, which made whole cells look
     * like "this style dropped the turn" instead of an unreliable injection.
     */
    private fun awaitStateQuiet(quietMs: Long = 300, timeoutMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastCount = reportCount.get()
        var lastChange = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            val current = reportCount.get()
            if (current != lastCount) {
                lastCount = current
                lastChange = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - lastChange >= quietMs) {
                return
            }
        }
    }

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
