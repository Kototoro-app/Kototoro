package org.skepsun.kototoro.details.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationSection

class EntityRelationSectionDeduplicationTest {

	@Test
	fun `duplicate tracking items keep the first item in each section`() {
		val first = relationItem(
			stableKey = "tracking:6:24348008396",
			name = "First",
		)
		val duplicate = relationItem(
			stableKey = "tracking:6:24348008396",
			name = "Duplicate",
		)
		val other = relationItem(
			stableKey = "tracking:6:42",
			name = "Other",
		)

		val result = listOf(
			EntityRelationSection(
				title = "Related",
				items = listOf(first, duplicate, other),
			),
		).deduplicateRelationItems()

		assertEquals(2, result.single().items.size)
		assertSame(first, result.single().items[0])
		assertSame(other, result.single().items[1])
	}

	@Test
	fun `the same tracking item may remain in different sections`() {
		val item = relationItem(
			stableKey = "tracking:6:24348008396",
			name = "Item",
		)

		val result = listOf(
			EntityRelationSection(title = "Related", items = listOf(item)),
			EntityRelationSection(title = "Recommendations", items = listOf(item)),
		).deduplicateRelationItems()

		assertEquals(listOf(item), result[0].items)
		assertEquals(listOf(item), result[1].items)
	}

	private fun relationItem(
		stableKey: String,
		name: String,
	) = EntityRelationItem(
		stableKey = stableKey,
		name = name,
		coverUrl = null,
	)
}
