package org.skepsun.kototoro.entitygraph.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class EntityGraphMappingTest {

	@Test
	fun `projection sync id is used when it is not owned by another entity`() {
		val entity = entity(id = 10L, syncId = "entity-sync-id")

		assertEquals(
			"projection:3:SRC:7:content",
			entity.resolveProjectionSyncId(
				projectionSyncId = "projection:3:SRC:7:content",
				conflictingEntityId = null,
			),
		)
	}

	@Test
	fun `existing sync id is kept when projection sync id belongs to another entity`() {
		val entity = entity(id = 10L, syncId = "entity-sync-id")

		assertEquals(
			"entity-sync-id",
			entity.resolveProjectionSyncId(
				projectionSyncId = "projection:3:SRC:7:content",
				conflictingEntityId = 20L,
			),
		)
	}

	@Test
	fun `blank sync id gets a unique fallback when projection sync id conflicts`() {
		val entity = entity(id = 10L, syncId = "")

		val resolved = entity.resolveProjectionSyncId(
			projectionSyncId = "projection:3:SRC:7:content",
			conflictingEntityId = 20L,
		)

		assertFalse(resolved.isBlank())
		assertNotEquals("projection:3:SRC:7:content", resolved)
	}

	private fun entity(id: Long, syncId: String): EntityRecord = EntityRecord(
		id = id,
		type = "WORK",
		syncId = syncId,
		primaryName = "作品",
		aliases = null,
		createdAt = 1L,
		lastAccessed = 1L,
		accessCount = 1,
	)
}
