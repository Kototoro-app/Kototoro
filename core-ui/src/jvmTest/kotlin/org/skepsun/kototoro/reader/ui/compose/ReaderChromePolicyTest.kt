package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where the three parts of the reader chrome go when the reader hides its controls: the bars leave
 * with the tap, a pinned floating column stays (issue #683).
 */
class ReaderChromePolicyTest {

    private fun resolve(
        controlsVisible: Boolean,
        progressAvailable: Boolean = true,
        chapterTitleAtBottom: Boolean = false,
        floatingControlsAvailable: Boolean = true,
        floatingControlsAllowed: Boolean = true,
        floatingControlsPinned: Boolean = false,
    ) = resolveReaderChromeVisibility(
        controlsVisible = controlsVisible,
        progressAvailable = progressAvailable,
        chapterTitleAtBottom = chapterTitleAtBottom,
        floatingControlsAvailable = floatingControlsAvailable,
        floatingControlsAllowed = floatingControlsAllowed,
        floatingControlsPinned = floatingControlsPinned,
    )

    @Test
    fun `controls up shows the bars and the floating column`() {
        assertEquals(
            ReaderChromeVisibility(
                visible = true,
                progressVisible = true,
                chapterTitleVisible = true,
                floatingControlsVisible = true,
            ),
            resolve(controlsVisible = true, chapterTitleAtBottom = true),
        )
    }

    @Test
    fun `controls hidden takes everything away by default`() {
        assertEquals(
            ReaderChromeVisibility(
                visible = false,
                progressVisible = false,
                chapterTitleVisible = false,
                floatingControlsVisible = false,
            ),
            resolve(controlsVisible = false, chapterTitleAtBottom = true),
        )
    }

    @Test
    fun `pinned keeps only the floating column`() {
        assertEquals(
            ReaderChromeVisibility(
                visible = true,
                progressVisible = false,
                chapterTitleVisible = false,
                floatingControlsVisible = true,
            ),
            resolve(controlsVisible = false, chapterTitleAtBottom = true, floatingControlsPinned = true),
        )
    }

    @Test
    fun `pinned without any floating control shows nothing`() {
        val visibility = resolve(
            controlsVisible = false,
            floatingControlsAvailable = false,
            floatingControlsPinned = true,
        )
        assertEquals(false, visibility.visible)
        assertEquals(false, visibility.floatingControlsVisible)
    }

    @Test
    fun `pinned still yields to a host that rules the column out`() {
        // The options panel and the TV layout use this to take the column away on their own terms.
        val visibility = resolve(
            controlsVisible = false,
            floatingControlsAllowed = false,
            floatingControlsPinned = true,
        )
        assertEquals(false, visibility.visible)
    }

    @Test
    fun `pinned changes nothing while the controls are up`() {
        assertEquals(
            resolve(controlsVisible = true, chapterTitleAtBottom = true),
            resolve(controlsVisible = true, chapterTitleAtBottom = true, floatingControlsPinned = true),
        )
    }

    @Test
    fun `chrome without bars still turns on for the floating column`() {
        // A reader with no page slider and no bottom title has nothing but the column to show.
        val visibility = resolve(controlsVisible = true, progressAvailable = false, chapterTitleAtBottom = false)
        assertEquals(true, visibility.visible)
        assertEquals(false, visibility.progressVisible)
        assertEquals(false, visibility.chapterTitleVisible)
        assertEquals(true, visibility.floatingControlsVisible)
    }
}
