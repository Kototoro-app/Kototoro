package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderInfoBarVisibilityTest {

	@Test
	fun `disabled control labels hide the reader information bar`() {
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = true, showControlLabels = false))
	}

	@Test
	fun `enabled control labels preserve the information bar setting`() {
		assertTrue(shouldShowReaderInfoBar(infoBarEnabled = true, showControlLabels = true))
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = false, showControlLabels = true))
	}
}
