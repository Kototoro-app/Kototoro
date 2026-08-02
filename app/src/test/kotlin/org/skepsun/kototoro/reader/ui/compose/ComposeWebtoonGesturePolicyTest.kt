package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeWebtoonGesturePolicyTest {

	@Test
	fun `transform gesture never hands vertical movement to the list`() {
		assertEquals(
			0,
			resolveWebtoonGestureBoundaryHandoff(
				scale = 2f,
				desiredY = 600f,
				boundedY = 500f,
				isTransformGesture = true,
			),
		)
	}

	@Test
	fun `single pointer pan keeps boundary handoff`() {
		assertEquals(
			-50,
			resolveWebtoonGestureBoundaryHandoff(
				scale = 2f,
				desiredY = 600f,
				boundedY = 500f,
				isTransformGesture = false,
			),
		)
	}

	@Test
	fun `multi pointer transform never starts a fling`() {
		assertFalse(
			shouldFlingWebtoonCanvas(
				singlePointerTransformed = true,
				hadMultiplePointers = true,
			),
		)
		assertTrue(
			shouldFlingWebtoonCanvas(
				singlePointerTransformed = true,
				hadMultiplePointers = false,
			),
		)
	}
}
