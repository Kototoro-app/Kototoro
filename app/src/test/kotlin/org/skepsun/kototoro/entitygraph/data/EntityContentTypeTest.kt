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

	@Test
	fun `legacy unresolved work type is inferred before compatible duplicate merge`() {
		val inferred = entity(1L, contentType = null).withInferredContentType(
			knownTypes = listOf(ContentType.MANGA, ContentType.HENTAI_MANGA),
		)
		val records = listOf(inferred, entity(2L, ContentType.HENTAI_MANGA))

		assertTrue(records.canMergeWorkContentTypes(allowCompatibleContentTypes = true))
	}

	@Test
	fun `legacy unresolved work type stays unresolved when projections cross media families`() {
		val inferred = entity(1L, contentType = null).withInferredContentType(
			knownTypes = listOf(ContentType.MANGA, ContentType.VIDEO),
		)

		assertFalse(listOf(inferred, entity(2L, ContentType.MANGA))
			.canMergeWorkContentTypes(allowCompatibleContentTypes = true))
	}

	@Test
	fun `unresolved duplicate source can use an explicit repair fallback`() {
		val inferred = entity(1L, contentType = null).withInferredContentType(
			knownTypes = emptyList(),
			fallback = ContentType.OTHER,
		)

		assertTrue(listOf(inferred, entity(2L, ContentType.OTHER))
			.canMergeWorkContentTypes(allowCompatibleContentTypes = true))
	}

	private fun entity(id: Long, contentType: ContentType?): EntityRecord {
		return EntityRecord(
			id = id,
			type = EntityType.WORK.name,
			contentType = contentType?.name,
			primaryName = "Work $id",
			aliases = null,
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 0,
		)
	}
}
