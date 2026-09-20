package org.skepsun.kototoro.reader

import android.app.Instrumentation
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
import org.skepsun.kototoro.R
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
 * Improvement plan 2026-09 §5.2 for the **continuous** hosts.
 *
 * [SceneReaderViewportSemanticsTest] covers the paged host; the continuous ones are the default for
 * their reading modes and were the ones the plan's semantics delivery had to extend. They share the
 * same contract, so this drives the framework `AccessibilityNodeInfo` tree the same way — the route
 * TalkBack uses — and asserts the two things the contract is about: the announced position is the
 * settled page window, and the page actions offered match where the reader actually is.
 *
 * The fixtures are sized so that exactly one page occupies the viewport: with a page smaller than the
 * viewport more than one is visible at once, the settled window spans several pages, and the
 * announced position stops being a single unambiguous page.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneContinuousViewportSemanticsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun webtoonViewportAnnouncesTheSettledPageAndExecutesTurns() = verifyContinuous(ContinuousReader.WEBTOON)

    @Test
    fun horizontalViewportAnnouncesTheSettledPageAndExecutesTurns() =
        verifyContinuous(ContinuousReader.HORIZONTAL)

    private fun verifyContinuous(reader: ContinuousReader) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // One page fills the viewport, so the settled window is a single page and its number is a
        // statement about where the reader is.
        val fixture = createFixture(context, reader, width = 1080, height = reader.fixtureHeight)
        val pages = (0 until 3).map { index ->
            ReaderPage(index.toLong(), fixture.absolutePath, null, null, 1, index, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fixture)))
        }
        val activePage = AtomicLong(Long.MIN_VALUE)
        val viewportWidth = AtomicInteger(0)
        val viewportHeight = AtomicInteger(0)
        val imageLoader = ImageLoader(context)

        fun description(pageNumber: Int): String =
            context.getString(R.string.reader_a11y_page_position, pageNumber, pages.size)

        fun nextLabel(): String = context.getString(R.string.reader_a11y_next_page)
        fun previousLabel(): String = context.getString(R.string.reader_a11y_previous_page)

        fun findNode(text: String): AccessibilityNodeInfo? =
            findNodeByContentDescription(instrumentation.uiAutomation?.rootInActiveWindow, text)

        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    viewportWidth.set(activity.window.decorView.width)
                    viewportHeight.set(activity.window.decorView.height)
                    activity.setContent {
                        MaterialTheme {
                            when (reader) {
                                ContinuousReader.WEBTOON -> ComposeSceneWebtoonReader(
                                    pages = pages,
                                    initialPage = 0,
                                    initialScroll = 0,
                                    imageLoader = imageLoader,
                                    imagePipeline = pipeline,
                                    onPagesChanged = { _, _, active -> activePage.set(active) },
                                    onInternalScrollChanged = { _, _ -> },
                                    isAnimationEnabled = false,
                                    readerBackgroundColor = Color.BLACK,
                                )

                                ContinuousReader.HORIZONTAL -> ComposeSceneHorizontalReader(
                                    pages = pages,
                                    initialPage = 0,
                                    initialScroll = 0,
                                    imageLoader = imageLoader,
                                    imagePipeline = pipeline,
                                    readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                    onPagesChanged = { _, _, active -> activePage.set(active) },
                                    onInternalScrollChanged = { _, _ -> },
                                    isAnimationEnabled = false,
                                    readerBackgroundColor = Color.BLACK,
                                )
                            }
                        }
                    }
                }

                // 1. The first page is announced, and it cannot offer "previous".
                waitUntil(instrumentation, "$reader did not announce page 1") { findNode(description(1)) != null }
                var node = findNode(description(1))!!
                assertTrue("$reader first page must offer next", hasAction(node, nextLabel()))
                assertTrue("$reader first page must not offer previous", !hasAction(node, previousLabel()))
                assertEquals("$reader initial reader state", pages[0].readerKey, activePage.get())

                // 2. The turn is executed through the accessibility action, not through a gesture.
                assertTrue("$reader next action refused", performAction(node, nextLabel()))
                waitUntil(instrumentation, "$reader did not announce page 2 after its own action") {
                    findNode(description(2)) != null
                }

                // 3. A middle page offers both directions.
                node = findNode(description(2))!!
                assertTrue("$reader middle page must offer next", hasAction(node, nextLabel()))
                assertTrue("$reader middle page must offer previous", hasAction(node, previousLabel()))

                // 4. The announced position never drifts into showing a raw scroll offset: it stays a
                //    page position over the total, which is the property TalkBack reads out.
                val descriptions = collectDescriptions(instrumentation)
                assertTrue(
                    "$reader announced a phrasing that is not a page position: $descriptions",
                    descriptions.all { it.startsWith("第 ") || it.startsWith("Page ") || it.contains("页") },
                )
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private enum class ContinuousReader(val fixtureHeight: Int) {
        WEBTOON(fixtureHeight = 3000),
        HORIZONTAL(fixtureHeight = 1500),
    }

    private fun createFixture(
        context: android.content.Context,
        reader: ContinuousReader,
        width: Int,
        height: Int,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
        }
        val file = File(context.cacheDir, "scene-continuous-semantics-${reader.name}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun collectDescriptions(instrumentation: Instrumentation): List<String> {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.contentDescription?.toString()?.let { found.add(it) }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return found
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo?, exact: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription == exact) return node
        for (index in 0 until node.childCount) {
            findNodeByContentDescription(node.getChild(index), exact)?.let { return it }
        }
        return null
    }

    private fun hasAction(node: AccessibilityNodeInfo, label: String): Boolean =
        node.actionList.any { it.label?.toString() == label }

    private fun performAction(node: AccessibilityNodeInfo, label: String): Boolean {
        val action = node.actionList.firstOrNull { it.label?.toString() == label } ?: return false
        return node.performAction(action.id)
    }

    private fun waitUntil(instrumentation: Instrumentation, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            dismissSystemDialogs(instrumentation)
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, false)
    }

    private fun dismissSystemDialogs(instrumentation: Instrumentation) {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return
        val button = root.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
