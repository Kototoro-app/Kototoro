package org.skepsun.kototoro.entitygraph.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType

class EntityContentTypeTest {

	@Test
	fun `default merge policy still requires the exact content type`() {
		val records = listOf(
			entity(1L, ContentType.MANGA),
			entity(2L, ContentType.MANHUA),
		)

		assertFalse(records.canMergeWorkContentTypes())
		assertTrue(records.canMergeWorkContentTypes(allowCompatibleContentTypes = true))
	}

	@Test
	fun `compatible merge policy still rejects cross media works`() {
		val records = listOf(
			entity(1L, ContentType.MANGA),
			entity(2L, ContentType.VIDEO),
		)

		assertFalse(records.canMergeWorkContentTypes(allowCompatibleContentTypes = true))
	}

	private fun entity(id: Long, contentType: ContentType): EntityRecord {
		return EntityRecord(
			id = id,
			type = EntityType.WORK.name,
			contentType = contentType.name,
			primaryName = "Work $id",
			aliases = null,
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 0,
		)
	}
}
