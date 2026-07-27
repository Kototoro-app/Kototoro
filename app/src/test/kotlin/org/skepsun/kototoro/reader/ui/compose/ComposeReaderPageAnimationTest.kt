package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

	@Test
	fun `simulation layers turning page above shaded revealed page`() {
		val turning = resolve(ReaderAnimation.SIMULATION, pageOffset = -0.5f)
		val revealed = resolve(ReaderAnimation.SIMULATION, pageOffset = 0.5f)

		assertEquals(1f, turning.zIndex)
		assertEquals(0.5f, turning.foldProgress)
		assertEquals(0f, turning.revealedPageShade)
		assertEquals(-0.5f, revealed.zIndex)
		assertEquals(0f, revealed.foldProgress)
		assertEquals(0.08f, revealed.revealedPageShade)
	}

	@Test
	fun `simulation mirrors turning edge in reversed mode`() {
		val transform = resolve(
			ReaderAnimation.SIMULATION,
			pageOffset = 0.5f,
			isReversed = true,
		)

		assertEquals(1f, transform.zIndex)
		assertEquals(0.5f, transform.foldProgress)
		assertEquals(0f, transform.rotationY)
		assertEquals(-0.5f, transform.translationFactor)
	}

	@Test
	fun `page curl uses touched corner for horizontal reading`() {
		val topRight = curl(touch = Offset(0.8f, 0.2f))
		val bottomRight = curl(touch = Offset(0.8f, 0.8f))
		val topLeft = curl(touch = Offset(0.2f, 0.2f), isReversed = true)

		assertEquals(Offset(1000f, 0f), topRight.corner)
		assertEquals(Offset(1000f, 1600f), bottomRight.corner)
		assertEquals(Offset(0f, 0f), topLeft.corner)
	}

	@Test
	fun `vertical page curl turns from touched bottom corner`() {
		val left = curl(touch = Offset(0.2f, 0.8f), isVertical = true)
		val right = curl(touch = Offset(0.8f, 0.8f), isVertical = true)

		assertEquals(Offset(0f, 1600f), left.corner)
		assertEquals(Offset(1000f, 1600f), right.corner)
	}

	@Test
	fun `page curl geometry stays finite throughout turn`() {
		listOf(0.001f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
			val geometry = curl(touch = Offset(0.76f, 0.84f), progress = progress)
			val points = listOf(
				geometry.touch,
				geometry.corner,
				geometry.control1,
				geometry.control2,
				geometry.start1,
				geometry.start2,
				geometry.end1,
				geometry.end2,
				geometry.vertex1,
				geometry.vertex2,
			)
			assertTrue(points.all { it.x.isFinite() && it.y.isFinite() }, "progress=$progress")
		}
	}

	private fun resolve(
		animation: ReaderAnimation,
		pageOffset: Float,
		isVertical: Boolean = false,
		isReversed: Boolean = false,
	) = resolveComposeReaderPageTransform(animation, pageOffset, isVertical, isReversed)

	private fun curl(
		touch: Offset,
		progress: Float = 0.5f,
		isVertical: Boolean = false,
		isReversed: Boolean = false,
	) = calculatePageCurlGeometry(
		size = Size(1000f, 1600f),
		progress = progress,
		touchFraction = touch,
		isVertical = isVertical,
		isReversed = isReversed,
	)
}
