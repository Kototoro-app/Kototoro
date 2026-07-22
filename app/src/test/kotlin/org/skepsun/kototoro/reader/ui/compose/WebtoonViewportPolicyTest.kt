package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebtoonViewportPolicyTest {

	@Test
	fun `unknown image reserves complete viewport`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 2000, internalScrollRangePx = 0),
			measureWebtoonViewport(2000, 1000, null, null),
		)
	}

	@Test
	fun `long image is capped to viewport with internal range`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 2000, internalScrollRangePx = 3000),
			measureWebtoonViewport(2000, 1000, 1000, 5000),
		)
	}

	@Test
	fun `short image keeps its natural fitted height`() {
		assertEquals(
			WebtoonViewportMeasurement(itemHeightPx = 750, internalScrollRangePx = 0),
			measureWebtoonViewport(2000, 1000, 2000, 1500),
		)
	}

	@Test
	fun `forward scroll is consumed by long image until its bottom`() {
		assertEquals(
			WebtoonInternalScrollConsumption(offsetPx = 700, consumedPx = 700),
			consumeWebtoonInternalScroll(offsetPx = 0, scrollRangePx = 1000, deltaPx = 700),
		)
		assertEquals(
			WebtoonInternalScrollConsumption(offsetPx = 1000, consumedPx = 300),
			consumeWebtoonInternalScroll(offsetPx = 700, scrollRangePx = 1000, deltaPx = 700),
		)
	}

	@Test
	fun `backward scroll is consumed by long image until its top`() {
		assertEquals(
			WebtoonInternalScrollConsumption(offsetPx = 250, consumedPx = -650),
			consumeWebtoonInternalScroll(offsetPx = 900, scrollRangePx = 1000, deltaPx = -650),
		)
		assertEquals(
			WebtoonInternalScrollConsumption(offsetPx = 0, consumedPx = -250),
			consumeWebtoonInternalScroll(offsetPx = 250, scrollRangePx = 1000, deltaPx = -650),
		)
	}

	@Test
	fun `restored internal scroll is clamped after image dimensions resolve`() {
		assertEquals(700, restoreWebtoonInternalScroll(savedOffsetPx = 700, scrollRangePx = 1000))
		assertEquals(1000, restoreWebtoonInternalScroll(savedOffsetPx = 1400, scrollRangePx = 1000))
		assertEquals(0, restoreWebtoonInternalScroll(savedOffsetPx = -20, scrollRangePx = 1000))
	}
}
