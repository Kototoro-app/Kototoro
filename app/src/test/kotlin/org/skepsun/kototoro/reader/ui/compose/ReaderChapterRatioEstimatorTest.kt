package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Chapter-wide aspect ratio estimation for pages that are still loading.
 *
 * Manga pages within a chapter share their aspect ratio closely, so the running average of
 * decoded pages predicts the real size of still-unknown pages: that is what lets the loading
 * placeholder geometry match the real image size before it decodes. The estimator only proposes
 * a relayout when the average has moved materially, to keep placeholder geometry churn bounded.
 */
class ReaderChapterRatioEstimatorTest {

    @Test
    fun `no proposal before enough pages decoded`() {
        val estimator = ReaderChapterRatioEstimator()

        assertNull(estimator.onDecoded(800, 1200))
        assertEquals(1f, estimator.currentRatio)
    }

    @Test
    fun `no proposal while the average stays within the threshold`() {
        val estimator = ReaderChapterRatioEstimator()

        assertNull(estimator.onDecoded(1000, 1000))
        // Average 0.95 vs applied 1.0 -> 5%, well inside the 10% threshold.
        assertNull(estimator.onDecoded(900, 1000))
    }

    @Test
    fun `proposes the running average once it moves materially`() {
        val estimator = ReaderChapterRatioEstimator()

        assertNull(estimator.onDecoded(800, 1000))
        // Average (0.8 + 0.7) / 2 = 0.75 vs applied 1.0 -> 25% off, proposal expected.
        assertEquals(0.75f, estimator.onDecoded(700, 1000)!!)
    }

    @Test
    fun `a proposal updates the applied baseline so equal averages stop proposing`() {
        val estimator = ReaderChapterRatioEstimator()

        estimator.onDecoded(800, 1000)
        estimator.onDecoded(700, 1000)

        // Average (0.8 + 0.7 + 0.75) / 3 = 0.75 vs applied 0.75 -> within threshold.
        assertNull(estimator.onDecoded(750, 1000))
    }

    @Test
    fun `non positive dimensions are ignored`() {
        val estimator = ReaderChapterRatioEstimator()

        assertNull(estimator.onDecoded(0, 1000))
        assertNull(estimator.onDecoded(800, 0))
        assertNull(estimator.onDecoded(0, 0))
        assertEquals(1f, estimator.currentRatio)
    }
}
