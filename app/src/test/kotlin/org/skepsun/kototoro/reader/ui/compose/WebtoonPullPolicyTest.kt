package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebtoonPullPolicyTest {

	@Test
	fun `top boundary accumulates and retracts pull distance`() {
		val pulled = WebtoonPullState().pullAtBoundary(40f, false, true, 100f)
		assertEquals(WebtoonPullState(topDistancePx = 40f), pulled)
		assertEquals(WebtoonPullState(topDistancePx = 15f), pulled.retract(-25f))
	}

	@Test
	fun `bottom boundary maps threshold release to next chapter`() {
		val pulled = WebtoonPullState().pullAtBoundary(-35f, true, false, 100f)
		assertEquals(WebtoonPullDirection.NEXT, pulled.release(30f))
	}

	@Test
	fun `pull below threshold does not switch chapter`() {
		assertNull(WebtoonPullState(topDistancePx = 29f).release(30f))
	}

	@Test
	fun `scrollable content does not accumulate boundary pull`() {
		val initial = WebtoonPullState()
		assertEquals(initial, initial.pullAtBoundary(40f, true, true, 100f))
	}
}
