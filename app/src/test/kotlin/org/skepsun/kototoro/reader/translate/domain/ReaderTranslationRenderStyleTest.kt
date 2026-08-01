package org.skepsun.kototoro.reader.translate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderTranslationRenderStyleTest {

	@Test
	fun `stored render styles are restored`() {
		assertEquals(
			ReaderTranslationRenderStyle.REPLACE,
			ReaderTranslationRenderStyle.fromPreference("REPLACE"),
		)
		assertEquals(
			ReaderTranslationRenderStyle.COMPACT_OVERLAY,
			ReaderTranslationRenderStyle.fromPreference("compact_overlay"),
		)
	}

	@Test
	fun `unknown render style falls back to compact overlay`() {
		assertEquals(
			ReaderTranslationRenderStyle.COMPACT_OVERLAY,
			ReaderTranslationRenderStyle.fromPreference("unknown"),
		)
	}
}
