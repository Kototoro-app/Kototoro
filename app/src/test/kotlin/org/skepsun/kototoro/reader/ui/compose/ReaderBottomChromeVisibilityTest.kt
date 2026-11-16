package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderBottomChromeVisibilityTest {

	@Test
	fun `title and progress share one bottom chrome visibility boundary`() {
		assertEquals(
			ReaderBottomChromeVisibility(
				visible = true,
				progressVisible = true,
				chapterTitleVisible = true,
			),
			resolveReaderBottomChromeVisibility(
				controlsVisible = true,
				progressAvailable = true,
				chapterTitleAtBottom = true,
			),
		)
		assertEquals(
			ReaderBottomChromeVisibility(
				visible = false,
				progressVisible = false,
				chapterTitleVisible = false,
			),
			resolveReaderBottomChromeVisibility(
				controlsVisible = false,
				progressAvailable = true,
				chapterTitleAtBottom = true,
			),
		)
	}

	@Test
	fun `floating controls share the same exit boundary as bottom chrome`() {
		assertEquals(
			ReaderBottomChromeVisibility(
				visible = true,
				progressVisible = false,
				chapterTitleVisible = false,
				floatingControlsVisible = true,
			),
			resolveReaderBottomChromeVisibility(
				controlsVisible = true,
				progressAvailable = false,
				chapterTitleAtBottom = false,
				floatingControlsAvailable = true,
			),
		)
		assertEquals(
			ReaderBottomChromeVisibility(
				visible = false,
				progressVisible = false,
				chapterTitleVisible = false,
				floatingControlsVisible = false,
			),
			resolveReaderBottomChromeVisibility(
				controlsVisible = false,
				progressAvailable = false,
				chapterTitleAtBottom = false,
				floatingControlsAvailable = true,
			),
		)
	}

	@Test
	fun `pinned floating controls outlive a hidden bottom chrome`() {
		// issue #683: the column stays, the bar-shaped chrome still leaves with the tap.
		assertEquals(
			ReaderBottomChromeVisibility(
				visible = true,
				progressVisible = false,
				chapterTitleVisible = false,
				floatingControlsVisible = true,
			),
			resolveReaderBottomChromeVisibility(
				controlsVisible = false,
				progressAvailable = true,
				chapterTitleAtBottom = true,
				floatingControlsAvailable = true,
				floatingControlsPinned = true,
			),
		)
	}
}
