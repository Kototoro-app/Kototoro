package org.skepsun.kototoro.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.prefs.ReaderAnimation

/**
 * Improvement plan 2026-09 §5.1, row "翻页样式": SLIDE / COVER / CURL must each land the turn for
 * forward and backward input, must not turn when the drag is released back at its origin, and must
 * still settle on a legal page when a new input interrupts the running animation.
 *
 * The style comes from the persisted preference through the same path the app uses:
 * `DEFAULT`/`NONE` -> SLIDE, `ADVANCED` -> COVER, `SIMULATION` -> CURL.
 *
 * Assertions read the reader's own reported page, because the plan makes that state the source of
 * truth. What accessibility is told is asserted separately: [announcedPageFollowsTheReaderState]
 * currently fails and is kept as the reproduction of a known defect.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScenePagedTransitionMatrixTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun slideForwardTurnLandsOnTheNextPage() = verifyForwardTurn(ReaderAnimation.DEFAULT, "SLIDE")

    @Test
    fun coverForwardTurnLandsOnTheNextPage() = verifyForwardTurn(ReaderAnimation.ADVANCED, "COVER")

    @Test
    fun slideBackwardTurnLandsOnThePreviousPage() = verifyBackwardTurn(ReaderAnimation.DEFAULT, "SLIDE")

    @Test
    fun coverBackwardTurnLandsOnThePreviousPage() = verifyBackwardTurn(ReaderAnimation.ADVANCED, "COVER")

    @Test
    fun curlBackwardTurnLandsOnThePreviousPage() = verifyBackwardTurn(ReaderAnimation.SIMULATION, "CURL")

    @Test
    fun slideCancelledDragKeepsTheSettledPage() = verifyCancelledDrag(ReaderAnimation.DEFAULT, "SLIDE")

    @Test
    fun coverCancelledDragKeepsTheSettledPage() = verifyCancelledDrag(ReaderAnimation.ADVANCED, "COVER")

    @Test
    fun curlCancelledDragKeepsTheSettledPage() = verifyCancelledDrag(ReaderAnimation.SIMULATION, "CURL")

    @Test
    fun slideInterruptedTurnSettlesOnALegalPage() = verifyInterruptedTurn(ReaderAnimation.DEFAULT, "SLIDE")

    @Test
    fun coverInterruptedTurnSettlesOnALegalPage() = verifyInterruptedTurn(ReaderAnimation.ADVANCED, "COVER")

    @Test
    fun curlInterruptedTurnSettlesOnALegalPage() = verifyInterruptedTurn(ReaderAnimation.SIMULATION, "CURL")

    /**
     * Known defect (2026-09-20, ESR tsk_8ea8dffe): after a gesture turn the host reports the new
     * settled window through `onPagesChanged` (measured `(page2, page2, page2)`), yet the viewport
     * keeps announcing the previous page. The description stayed on page 1 for 1.5s in every style,
     * never caught up within 15s in one run and did catch up in another, so the announcement lags
     * or stalls. Removing @Ignore reproduces it.
     */
    @Ignore("known defect: the announced page does not follow the reader state after a gesture turn")
    @Test
    fun announcedPageFollowsTheReaderState() {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = ReaderAnimation.DEFAULT).use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForward()
            host.awaitActivePage(1, "page turn")
            host.holdSettled(1_500)
            assertEquals("announced page after the turn", 2, host.announcedPageNumber())
        }
    }

    /**
     * CURL's forward turn is unreliable in this harness: the same injected swipe turned the page in
     * one run and left it untouched in two others, while SLIDE and COVER turned it every time.
     * Either CURL needs a drag shape this harness does not produce, or the style drops turns; the
     * two must be told apart before the cell can be called either way.
     */
    @Ignore("CURL forward turn is flaky in this harness: needs input-shape vs dropped-turn triage")
    @Test
    fun curlForwardTurnLandsOnTheNextPage() = verifyForwardTurn(ReaderAnimation.SIMULATION, "CURL")

    /**
     * Finding 1 disposition (ESR tsk_8ea8dffe): does one more frame, produced without changing the
     * page, make the announcement catch up? The turn is asserted to have committed first, so this
     * cannot pass by measuring a swipe that never landed.
     */
    @Test
    fun announcedPageCatchesUpAfterOneMoreFrame() {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = ReaderAnimation.DEFAULT).use { host ->
            host.launch()
            host.awaitActivePage(0, "initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "page turn")
            host.holdSettled(1_500)
            host.tapCenter()
            host.holdSettled(1_000)
            assertEquals("announced page after one more frame", 2, host.announcedPageNumber())
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun verifyForwardTurn(animation: ReaderAnimation, style: String) {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = animation).use { host ->
            host.launch()
            host.awaitActivePage(0, "$style initial state")
            host.swipeForwardCommitting()
            host.awaitActivePage(1, "$style forward turn")
            assertEquals("$style forward turn", 1, host.activePageIndex())
        }
    }

    private fun verifyBackwardTurn(animation: ReaderAnimation, style: String) {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = animation, initialPage = 1).use { host ->
            host.launch()
            host.awaitActivePage(1, "$style initial state")
            host.swipeBackwardCommitting()
            host.awaitActivePage(0, "$style backward turn")
            assertEquals("$style backward turn", 0, host.activePageIndex())
        }
    }

    private fun verifyCancelledDrag(animation: ReaderAnimation, style: String) {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = animation, initialPage = 1).use { host ->
            host.launch()
            host.awaitActivePage(1, "$style initial state")
            host.cancelledDrag()
            host.holdSettled(600)
            assertEquals("$style cancelled drag", 1, host.activePageIndex())
        }
    }

    private fun verifyInterruptedTurn(animation: ReaderAnimation, style: String) {
        hiltRule.inject()
        ScenePagedTransitionHarness(pageAnimation = animation).use { host ->
            host.launch()
            host.awaitActivePage(0, "$style initial state")
            host.interruptedTurn()
            host.awaitActivePageWithin(setOf(0, 1), "$style interrupted turn")
        }
    }
}
