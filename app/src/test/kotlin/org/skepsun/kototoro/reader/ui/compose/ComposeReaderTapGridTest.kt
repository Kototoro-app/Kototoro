package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.domain.TapGridArea

class ComposeReaderTapGridTest {

	@Test
	fun `maps viewport thirds to grid areas`() {
		val size = IntSize(300, 600)
		assertEquals(TapGridArea.TOP_LEFT, resolveTapGridArea(Offset(10f, 10f), size))
		assertEquals(TapGridArea.CENTER, resolveTapGridArea(Offset(150f, 300f), size))
		assertEquals(TapGridArea.BOTTOM_RIGHT, resolveTapGridArea(Offset(299f, 599f), size))
	}

	@Test
	fun `rejects an empty viewport`() {
		assertNull(resolveTapGridArea(Offset.Zero, IntSize.Zero))
	}
}
