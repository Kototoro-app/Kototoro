package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderAnimation

class ComposeReaderPageAnimationTest {

	@Test
	fun `none animation holds current page until halfway`() {
		assertEquals(
			-0.4f,
			resolve(ReaderAnimation.NONE, pageOffset = 0.4f).translationFactor,
		)
		assertEquals(1f, resolve(ReaderAnimation.NONE, pageOffset = 0.6f).translationFactor)
	}

	@Test
	fun `advanced horizontal animation rotates outgoing page around reading edge`() {
		val standard = resolve(ReaderAnimation.ADVANCED, pageOffset = -0.5f)
		val reversed = resolve(ReaderAnimation.ADVANCED, pageOffset = 0.5f, isReversed = true)

		assertEquals(-60f, standard.rotationY)
		assertEquals(0f, standard.transformOrigin.pivotFractionX)
		assertEquals(60f, reversed.rotationY)
		assertEquals(1f, reversed.transformOrigin.pivotFractionX)
	}

	@Test
	fun `advanced vertical animation uses top-biased horizontal hinge`() {
		val transform = resolve(ReaderAnimation.ADVANCED, pageOffset = -0.5f, isVertical = true)

		assertEquals(60f, transform.rotationX)
		assertEquals(0.5f, transform.transformOrigin.pivotFractionX)
		assertEquals(0.2f, transform.transformOrigin.pivotFractionY)
	}

	private fun resolve(
		animation: ReaderAnimation,
		pageOffset: Float,
		isVertical: Boolean = false,
		isReversed: Boolean = false,
	) = resolveComposeReaderPageTransform(animation, pageOffset, isVertical, isReversed)
}
