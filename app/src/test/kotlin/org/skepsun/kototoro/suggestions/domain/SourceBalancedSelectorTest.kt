package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceBalancedSelectorTest {

	@Test
	fun `selects one item per source before starting next round`() {
		val candidates = listOf("a1", "a2", "a3", "b1", "b2", "c1")

		val result = candidates.selectBalancedBySource(6, 3) { it.first() }

		assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "a3"), result)
	}

	@Test
	fun `enforces per source limit and fills from remaining sources`() {
		val candidates = listOf("a1", "a2", "a3", "b1", "b2", "b3", "c1")

		val result = candidates.selectBalancedBySource(6, 2) { it.first() }

		assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), result)
	}

	@Test
	fun `returns empty result for invalid limits`() {
		assertEquals(emptyList<String>(), listOf("a1").selectBalancedBySource(0, 1) { it.first() })
		assertEquals(emptyList<String>(), listOf("a1").selectBalancedBySource(1, 0) { it.first() })
	}

	@Test
	fun `preferred sources fill their existing per-source allowance first`() {
		val candidates = listOf("a1", "a2", "b1", "b2", "c1", "c2")

		val result = candidates.selectBalancedByPreferredSource(
			limit = 5,
			perSourceLimit = 2,
			preferredSources = setOf('c'),
		) { it.first() }

		assertEquals(listOf("c1", "c2", "a1", "b1", "a2"), result)
	}
}
