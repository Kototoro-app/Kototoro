package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderControl

class ReaderFloatingControlsTest {

	@Test
	fun `stored floating controls are normalized to four supported entries`() {
		val controls = ReaderControl.limitFloatingControls(ReaderControl.entries.toSet())

		assertEquals(ReaderControl.FLOATING.take(4).toSet(), controls)
	}

	@Test
	fun `configured floating controls are limited to four`() {
		val controls = resolveReaderFloatingControls(
			configured = ReaderControl.FLOATING,
			translationAvailable = true,
			translationContextualVisible = false,
		)

		assertEquals(ReaderControl.FLOATING.take(4), controls)
	}

	@Test
	fun `contextual translation is appended when space is available`() {
		val controls = resolveReaderFloatingControls(
			configured = setOf(ReaderControl.SCREEN_ROTATION, ReaderControl.BOOKMARK),
			translationAvailable = true,
			translationContextualVisible = true,
		)

		assertEquals(
			listOf(ReaderControl.SCREEN_ROTATION, ReaderControl.BOOKMARK, ReaderControl.TRANSLATE),
			controls,
		)
	}

	@Test
	fun `contextual translation keeps total at four`() {
		val controls = resolveReaderFloatingControls(
			configured = setOf(
				ReaderControl.SCREEN_ROTATION,
				ReaderControl.SAVE_PAGE,
				ReaderControl.TIMER,
				ReaderControl.BOOKMARK,
			),
			translationAvailable = true,
			translationContextualVisible = true,
		)

		assertEquals(
			listOf(
				ReaderControl.SCREEN_ROTATION,
				ReaderControl.SAVE_PAGE,
				ReaderControl.TIMER,
				ReaderControl.TRANSLATE,
			),
			controls,
		)
	}

	@Test
	fun `translation control is hidden when translation is unavailable`() {
		val controls = resolveReaderFloatingControls(
			configured = setOf(ReaderControl.SCREEN_ROTATION, ReaderControl.TRANSLATE),
			translationAvailable = false,
			translationContextualVisible = true,
		)

		assertEquals(listOf(ReaderControl.SCREEN_ROTATION), controls)
	}

	@Test
	fun `chapters and pages is never exposed as a floating control`() {
		val controls = ReaderControl.limitFloatingControls(setOf(ReaderControl.PAGES_SHEET, ReaderControl.TRANSLATE))

		assertEquals(setOf(ReaderControl.TRANSLATE), controls)
	}
}
