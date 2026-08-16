package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderTopBarPresentationTest {

	@Test
	fun `disabled control labels use an icon only chapter control`() {
		assertEquals(
			ReaderTopBarPresentation.ICON_ONLY,
			resolveReaderTopBarPresentation(showControlLabels = false),
		)
	}

	@Test
	fun `enabled control labels show title and chapter text`() {
		assertEquals(
			ReaderTopBarPresentation.LABELS,
			resolveReaderTopBarPresentation(showControlLabels = true),
		)
	}
}
