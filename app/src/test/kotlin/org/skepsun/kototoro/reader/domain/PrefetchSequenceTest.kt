package org.skepsun.kototoro.reader.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrefetchSequenceTest {

	@Test
	fun `only the latest call is current`() {
		val sequence = PrefetchSequence()
		val first = sequence.next()
		val second = sequence.next()

		assertFalse(sequence.isLatest(first))
		assertTrue(sequence.isLatest(second))
	}

	@Test
	fun `invalidate makes all earlier calls stale`() {
		val sequence = PrefetchSequence()
		val call = sequence.next()

		sequence.invalidate()

		assertFalse(sequence.isLatest(call))
	}

	@Test
	fun `a call issued after invalidate is current`() {
		val sequence = PrefetchSequence()
		sequence.next()
		sequence.invalidate()
		val call = sequence.next()

		assertTrue(sequence.isLatest(call))
	}
}
