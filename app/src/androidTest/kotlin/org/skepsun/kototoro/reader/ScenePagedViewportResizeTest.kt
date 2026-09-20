package org.skepsun.kototoro.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.ZoomMode

/** `lower to upper` of the settled window, in page indexes. */
private fun settledWindow(host: ScenePagedTransitionHarness): Pair<Int, Int> =
    host.reportedWindow().let { it.first to it.second }

/**
 * Improvement plan 2026-09 section 5.1, row "lifecycle": a viewport resize - rotation, split-screen,
 * foldable posture change - must keep the reading position.
 *
 * The plan fixes the judgement: restore by chapter/page identity and a normalized in-page anchor,
 * never by comparing absolute pixels before and after. These cases therefore assert the page the
 * reader reports, not the offset it happens to sit at.
 *
 * Resizing here is a real resize of the reader's own viewport: the composition and the activity
 * both stay alive, so whatever the host does about it is what the app does about it.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedViewportResizeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * The opening cell: a reader that has never turned a page is on page 1 both before and after a
     * resize, so this cannot regress and is not evidence that the position is preserved.
     */
    @Test
    fun resizeOnTheFirstPageStaysOnTheFirstPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness().use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.resizeViewport(0.7f)
            host.awaitActivePage(0, "resize on page 1")
        }
    }

    /**
     * The defect cell (ESR tsk_8d1cbe98): page 2 was settled before the resize, so the reader must
     * still be on page 2 afterwards.
     */
    @Test
    fun resizeKeepsTheSettledPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness().use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "page turn")
            host.holdSettled(600)
            assertEquals("reader state before the resize", 1 to 1, settledWindow(host))

            host.resizeViewport(0.7f)
            host.holdSettled(1_000)
            assertEquals("reader state after the resize", 1 to 1, settledWindow(host))
        }
    }

    /**
     * The same cell under a mode that makes the page larger than its slot: the position is carried
     * by the slot, not by the slot's pixel offset, so the resize has to re-resolve the fit too.
     */
    @Test
    fun resizeInAScaledFitModeKeepsTheSettledPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness(zoomMode = ZoomMode.KEEP_START).use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "page turn")
            host.resizeViewport(0.7f)
            host.holdSettled(1_000)
            assertEquals("reader state after a resize in KEEP_START", 1 to 1, settledWindow(host))
        }
    }

    /**
     * Two resizes in a row: the second one is the one that catches an implementation which only
     * remembers the launch page rather than tracking the position.
     */
    @Test
    fun consecutiveResizesKeepTheSettledPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness().use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "page turn")
            host.resizeViewport(0.8f)
            host.resizeViewport(0.6f)
            host.holdSettled(1_000)
            assertEquals("reader state after two resizes", 1 to 1, settledWindow(host))
        }
    }

    /** A resize must not silently change which page the reader announces. */
    @Test
    fun resizeKeepsTheAnnouncedPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = ReaderAnimation.DEFAULT).use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "page turn")
            host.holdSettled(1_500)
            host.resizeViewport(0.7f)
            host.holdSettled(1_500)
            assertEquals("announced page after the resize", 2, host.announcedPageNumber())
        }
    }

    // --- Rebuild path (section 5.1 "lifecycle"): what the host does when it re-creates the reader --
    //
    // Rotation in this app is an Activity recreation, and the host re-creates the reader rather than
    // resizing it: it passes the restored position down as the current launch page and, on a layout
    // change, as a programmatic page request. Both are exercised here, because a lifecycle case that
    // only ever relies on the initial launch page does not test the path the app actually takes.

    @Test
    fun aRebuiltReaderOpensOnTheRestoredPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness(initialPage = 2).use { host ->
            host.launch()
            host.awaitActivePage(2, "rebuilt reader restored the position")
        }
    }

    @Test
    fun aProgrammaticRequestLandsOnTheRequestedPage() {
        hiltRule.inject()
        ScenePagedTransitionHarness().use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.requestPage(2)
            host.awaitActivePage(2, "programmatic page request")
        }
    }

    @Test
    fun aProgrammaticRequestSurvivesAResize() {
        hiltRule.inject()
        ScenePagedTransitionHarness().use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.requestPage(2)
            host.awaitActivePage(2, "programmatic page request")
            host.resizeViewport(0.7f)
            host.holdSettled(1_000)
            assertEquals("settled window after requesting a page and resizing", 2 to 2, settledWindow(host))
        }
    }
}
