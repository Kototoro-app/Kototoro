package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderBackground

class ComposeReaderBackgroundTest {

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
