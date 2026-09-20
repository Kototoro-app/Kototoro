package org.skepsun.kototoro.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeScenePagedReader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * Improvement plan 2026-09 §5.2 acceptance: the Scene paged host's viewport is locatable
 * through accessibility semantics (settled page position as content description) and a page
 * turn can be EXECUTED through the exposed custom action — the same route TalkBack uses.
 *
 * This deliberately drives the framework AccessibilityNodeInfo tree instead of Compose test
 * tags: test tags aid tooling, but the accessibility node contract is what TalkBack
 * actually consumes.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SceneReaderViewportSemanticsTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun viewportSemanticsExposePagePositionAndExecutePageTurns() {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val fixture = File(context.cacheDir, "scene-paged-semantics.png")
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val pages = (0 until 4).map { index ->
            ReaderPage(index.toLong(), fixture.toURI().toString(), null, null, 1, index, LocalMangaSource)
        }
        val pipeline = object : ComposeReaderImagePipeline {
            override fun observe(page: ReaderPage, force: Boolean) =
                flowOf(ComposeReaderImageState.OriginalReady(Uri.fromFile(fixture)))
        }
        val activePage = AtomicLong(Long.MIN_VALUE)
        val imageLoader = ImageLoader(context)

        fun expectedDescription(pageNumber: Int): String =
            context.getString(R.string.reader_a11y_page_position, pageNumber, pages.size)

        fun nextPageLabel(): String = context.getString(R.string.reader_a11y_next_page)
        fun previousPageLabel(): String = context.getString(R.string.reader_a11y_previous_page)

        try {
            ActivityScenario.launch<IdleProbeActivity>(
                Intent(context, IdleProbeActivity::class.java)
                    .putExtra("scene_recovery", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            ComposeScenePagedReader(
                                pages = pages,
                                initialPage = 0,
                                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                                imageLoader = imageLoader,
                                imagePipeline = pipeline,
                                onPagesChanged = { _, _, active -> activePage.set(active) },
                                zoomMode = ZoomMode.FIT_CENTER,
                                isAnimationEnabled = false,
                                readerBackground = ReaderBackground.BLACK,
                            )
                        }
                    }
                }

                // 1. The viewport announces the settled page position.
                waitUntil("viewport description did not settle on page 1") {
                    findViewportNode(instrumentation, expectedDescription(1)) != null
                }
                assertEquals(pages[0].readerKey, activePage.get())

                // 2. First page: "next" is offered, "previous" is not.
                var node = findViewportNode(instrumentation, expectedDescription(1))!!
                assertTrue(hasAction(node, nextPageLabel()))
                assertTrue(!hasAction(node, previousPageLabel()))

                // 3. Execute the page turn through the accessibility custom action.
                assertTrue(performAction(node, nextPageLabel()))

                waitUntil("page turn via accessibility action did not settle on page 2") {
                    findViewportNode(instrumentation, expectedDescription(2)) != null
                }
                assertEquals(pages[1].readerKey, activePage.get())

                // 4. Middle page: both directions are offered.
                node = findViewportNode(instrumentation, expectedDescription(2))!!
                assertTrue(hasAction(node, nextPageLabel()))
                assertTrue(hasAction(node, previousPageLabel()))

                // 5. Walk to the last page; "next" must no longer be offered there.
                assertTrue(performAction(node, nextPageLabel()))
                waitUntil("did not settle on page 3") { findViewportNode(instrumentation, expectedDescription(3)) != null }
                node = findViewportNode(instrumentation, expectedDescription(3))!!
                assertTrue(performAction(node, nextPageLabel()))
                waitUntil("did not settle on page 4") { findViewportNode(instrumentation, expectedDescription(4)) != null }
                assertEquals(pages[3].readerKey, activePage.get())

                node = findViewportNode(instrumentation, expectedDescription(4))!!
                assertTrue(!hasAction(node, nextPageLabel()))
                assertTrue(hasAction(node, previousPageLabel()))

                // 6. Turning back re-offers "next".
                assertTrue(performAction(node, previousPageLabel()))
                waitUntil("did not settle back on page 3") { findViewportNode(instrumentation, expectedDescription(3)) != null }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Accessibility tree helpers
    // ---------------------------------------------------------------------------------------------

    private fun findViewportNode(
        instrumentation: android.app.Instrumentation,
        contentDescription: String,
    ): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return null
        return findNodeByContentDescription(root, contentDescription)
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

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (SystemClock.uptimeMillis() < deadline) {
            dismissSystemDialogs(instrumentation)
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, false)
    }

    private fun dismissSystemDialogs(instrumentation: android.app.Instrumentation) {
        val root = instrumentation.uiAutomation?.rootInActiveWindow ?: return
        val button = root.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
