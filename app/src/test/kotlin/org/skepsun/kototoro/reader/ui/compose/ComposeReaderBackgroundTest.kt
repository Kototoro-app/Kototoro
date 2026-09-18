package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderBackground

class ComposeReaderBackgroundTest {

	@Test
	fun `scene fixed white background ignores book tint`() {
		assertEquals(
			Color.WHITE,
			resolveScenePagedBackground(ReaderBackground.WHITE, Color.WHITE, null, BOOK_TINT),
		)
	}

	@Test
	fun `scene non automatic backgrounds preserve configured color`() {
		for (background in ReaderBackground.entries.filter { it != ReaderBackground.AUTO }) {
			assertEquals(
				Color.WHITE,
				resolveScenePagedBackground(background, Color.WHITE, GREEN, BOOK_TINT),
			)
		}
	}

	@Test
	fun `scene automatic background tints white samples and white fallback only`() {
		assertEquals(BOOK_TINT, resolveScenePagedBackground(ReaderBackground.AUTO, Color.BLACK, Color.WHITE, BOOK_TINT))
		assertEquals(BOOK_TINT, resolveScenePagedBackground(ReaderBackground.AUTO, Color.WHITE, null, BOOK_TINT))
		assertEquals(GREEN, resolveScenePagedBackground(ReaderBackground.AUTO, Color.WHITE, GREEN, BOOK_TINT))
		assertEquals(Color.WHITE, resolveScenePagedBackground(ReaderBackground.AUTO, Color.WHITE, null, null))
	}

	@Test
	fun `fixed background ignores sampled page colors`() {
		assertEquals(
			Color.BLACK,
			resolveDoublePageBackground(ReaderBackground.BLACK, Color.BLACK, Color.WHITE, Color.WHITE),
		)
	}

	@Test
	fun `automatic double page background merges both sampled colors`() {
		val result = resolveDoublePageBackground(
			background = ReaderBackground.AUTO,
			configuredColor = Color.WHITE,
			firstAutoColor = GREEN,
			secondAutoColor = DARK_GREEN,
		)

		assertNotEquals(GREEN, result)
		assertNotEquals(DARK_GREEN, result)
	}

	@Test
	fun `automatic background keeps configured fallback before sampling completes`() {
		assertEquals(
			Color.WHITE,
			resolveDoublePageBackground(ReaderBackground.AUTO, Color.WHITE, null, null),
		)
	}

	@Test
	fun `book tint applies only to a pure white automatic background`() {
		assertEquals(BOOK_TINT, applyAutomaticBookBackgroundTint(Color.WHITE, BOOK_TINT))
		assertEquals(GREEN, applyAutomaticBookBackgroundTint(GREEN, BOOK_TINT))
	}

	private companion object {
		const val GREEN = -0xC07095
		const val DARK_GREEN = -0xD077A9
		const val BOOK_TINT = -0x16
	}
}
