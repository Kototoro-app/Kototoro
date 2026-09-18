package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Zoom must not silently present an upscaled bitmap.
 *
 * Constraining the request to the plan's target keeps a 6000x9000 page at ~13.5MB instead of
 * ~216MB, but that target is resolved for the fit-to-screen scale. When the reader zooms in, the
 * presented bitmap has to grow with the camera or the page goes soft, so the camera settle path
 * needs a rule for when a sampled page must be decoded again.
 */
class ReaderZoomReacquirePolicyTest {

    private val viewportWidth = 1280

    @Test
    fun `zooming past the decoded resolution asks for a new decode`() {
        // 1500px of decoded page shown across a 1280px viewport at 2.5x needs about 3200px.
        assertTrue(
            shouldReacquireForZoom(
                scale = 2.5f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
            ),
        )
    }

    @Test
    fun `a settle at fit scale never triggers a decode`() {
        assertFalse(
            shouldReacquireForZoom(
                scale = 1f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
            ),
        )
    }

    @Test
    fun `an already detailed decode is left alone`() {
        // A 6000px decode shown at 2.5x across 1280px still has headroom, so re-decoding it would
        // only churn memory and cache entries.
        assertFalse(
            shouldReacquireForZoom(
                scale = 2.5f,
                decodedWidthPx = 6000,
                viewportWidthPx = viewportWidth,
            ),
        )
    }

    @Test
    fun `marginal shortfalls stay below the re-decode threshold`() {
        // 1280 x 1.1 = 1408 needed against 1500 decoded: a 6% shortfall is not worth a new decode.
        assertFalse(
            shouldReacquireForZoom(
                scale = 1.1f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
            ),
        )
    }

    @Test
    fun `repeating the same zoom request is not retried`() {
        // A settle fires repeatedly while the reader pans or settles. Re-decoding the same target
        // width every time churns tens of megabytes per attempt without ever improving the page, so
        // an attempt at a given target is only made once.
        val needed = 3200

        assertTrue(
            shouldAttemptZoomReacquire(
                scale = 2.5f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
                lastAttemptWidthPx = null,
            ),
        )
        assertFalse(
            shouldAttemptZoomReacquire(
                scale = 2.5f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
                lastAttemptWidthPx = needed,
            ),
        )
    }

    @Test
    fun `a deeper zoom is a new request`() {
        assertTrue(
            shouldAttemptZoomReacquire(
                scale = 4f,
                decodedWidthPx = 1500,
                viewportWidthPx = viewportWidth,
                lastAttemptWidthPx = 3200,
            ),
        )
    }
}
