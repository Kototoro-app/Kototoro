package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

class RegionSubSamplingImageSourceTest {

	@Test
	fun `keeps existing split coordinates for odd image widths`() {
		val imageSize = IntSize(width = 1001, height = 1500)

		assertEquals(IntRect(0, 0, 500, 1500), pageSplitRegion(imageSize, ReaderPageSplit.LEFT))
		assertEquals(IntRect(501, 0, 1001, 1500), pageSplitRegion(imageSize, ReaderPageSplit.RIGHT))
	}

	@Test
	fun `none exposes the complete image`() {
		assertEquals(
			IntRect(0, 0, 800, 1200),
			pageSplitRegion(IntSize(800, 1200), ReaderPageSplit.NONE),
		)
	}

	@Test
	fun `splits after applying crop bounds`() {
		val crop = IntRect(10, 20, 1011, 1520)

		assertEquals(IntRect(10, 20, 510, 1520), imageRegion(IntSize(1200, 1800), crop, ReaderPageSplit.LEFT))
		assertEquals(IntRect(511, 20, 1011, 1520), imageRegion(IntSize(1200, 1800), crop, ReaderPageSplit.RIGHT))
	}
}
