package org.skepsun.kototoro.work.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.work.domain.WorkMigrationState
import org.skepsun.kototoro.work.domain.WorkProjectionBindingAction
import org.skepsun.kototoro.work.domain.WorkProjectionBindingResult

class DefaultWorkResolverTest {

	private val dao = mockk<EntityGraphDao>(relaxed = true)
	private val db = mockk<MangaDatabase> {
		every { getEntityGraphDao() } returns dao
	}
	private val entityGraphRepository = mockk<EntityGraphRepository>(relaxed = true)
	private val resolver = DefaultWorkResolver(db, entityGraphRepository)

	@Test
	fun `resolveByMangaId returns review identity when local binding is missing`() = runTest {
		coEvery {
			dao.findActiveBindingsBySources(listOf("local_manga", "0"), listOf("10"))
		} returns emptyList()

		val identity = resolver.resolveByMangaId(10L)

		assertNull(identity.entityId)
		assertEquals(10L, identity.requestedMangaId)
		assertNull(identity.preferredMangaId)
		assertEquals(emptySet<Long>(), identity.localMangaIds)
		assertEquals(WorkMigrationState.NEEDS_REVIEW, identity.migrationState)
	}

	@Test
	fun `resolveManyByMangaIds prefers local_manga over legacy zero source`() = runTest {
		coEvery {
			dao.findActiveBindingsBySources(listOf("local_manga", "0"), listOf("10"))
		} returns listOf(
			binding(entityId = 1L, source = "0", mangaId = 10L),
			binding(entityId = 2L, source = "local_manga", mangaId = 10L),
		)
		coEvery { dao.findActiveLocalBindingsByEntities(listOf(2L)) } returns listOf(
			binding(entityId = 2L, source = "local_manga", mangaId = 10L),
			binding(entityId = 2L, source = "local_manga", mangaId = 11L),
		)
		coEvery { dao.findEntityPrefsByIds(listOf(2L)) } returns listOf(
			prefs(entityId = 2L, preferredLocalMangaId = 11L),
		)

		val identity = resolver.resolveManyByMangaIds(listOf(10L)).getValue(10L)

		assertEquals(2L, identity.entityId)
		assertEquals(10L, identity.requestedMangaId)
		assertEquals(11L, identity.preferredMangaId)
		assertEquals(setOf(10L, 11L), identity.localMangaIds)
		assertEquals(WorkMigrationState.VALID, identity.migrationState)
	}

	@Test
	fun `selectPreferredProjection falls back when stored preferred projection is inactive`() = runTest {
		coEvery { dao.findEntityPrefs(5L) } returns prefs(entityId = 5L, preferredLocalMangaId = 99L)
		coEvery { dao.findActiveLocalBindingsByEntity(5L) } returns listOf(
			binding(entityId = 5L, source = "local_manga", mangaId = 20L),
			binding(entityId = 5L, source = "0", mangaId = 21L),
		)

		assertEquals(20L, resolver.selectPreferredProjection(5L))
	}

	@Test
	fun `resolveByEntityId ignores non work entities`() = runTest {
		coEvery { dao.findEntity(7L) } returns entity(id = 7L, type = EntityType.PERSON)

		assertNull(resolver.resolveByEntityId(7L))
	}

	@Test
	fun `bindProjectionToEntity attaches an unbound projection`() = runTest {
		val owners = mutableMapOf<Long, Long>()
		val localIds = mutableMapOf(1L to mutableSetOf<Long>())
		stubBindingState(owners, localIds)
		coEvery { entityGraphRepository.attachLocalWorkProjectionToEntity(1L, any(), 1f, true) } answers {
			owners[30L] = 1L
			localIds.getValue(1L).add(30L)
			true
		}

		val result = resolver.bindProjectionToEntity(1L, content(30L))

		assertEquals(WorkProjectionBindingAction.ATTACHED, result.success().action)
		coVerify(exactly = 0) { entityGraphRepository.mergeEntities(any(), any(), any(), any()) }
		coVerify(exactly = 0) { entityGraphRepository.moveLocalWorkProjectionToEntity(any(), any(), any(), any()) }
	}

	@Test
	fun `bindProjectionToEntity attaches an unbound projection from the same media family`() = runTest {
		val owners = mutableMapOf<Long, Long>()
		val localIds = mutableMapOf(1L to mutableSetOf<Long>())
		stubBindingState(owners, localIds)
		coEvery { entityGraphRepository.attachLocalWorkProjectionToEntity(1L, any(), 1f, true) } answers {
			owners[30L] = 1L
			localIds.getValue(1L).add(30L)
			true
		}

		val result = resolver.bindProjectionToEntity(
			targetEntityId = 1L,
			projection = content(30L, title = "A translated title", contentType = ContentType.MANHUA),
		)

		assertEquals(WorkProjectionBindingAction.ATTACHED, result.success().action)
	}

	@Test
	fun `bindProjectionToEntity rejects an unbound projection from another media family`() = runTest {
		val owners = mutableMapOf<Long, Long>()
		val localIds = mutableMapOf(1L to mutableSetOf<Long>())
		stubBindingState(owners, localIds)

		val result = resolver.bindProjectionToEntity(
			targetEntityId = 1L,
			projection = content(30L, title = "Same title", contentType = ContentType.VIDEO),
		)

		assertTrue(result is WorkProjectionBindingResult.Conflict)
		assertEquals(
			org.skepsun.kototoro.work.domain.WorkProjectionBindingConflict.TARGET_CONTENT_TYPE_CONFLICT,
			(result as WorkProjectionBindingResult.Conflict).reason,
		)
		coVerify(exactly = 0) { entityGraphRepository.attachLocalWorkProjectionToEntity(any(), any(), any(), any()) }
	}

	@Test
	fun `bindProjectionToEntity reuses a projection already owned by target`() = runTest {
		val owners = mutableMapOf(30L to 1L)
		val localIds = mutableMapOf(1L to mutableSetOf(30L, 31L))
		stubBindingState(owners, localIds)
		coEvery { entityGraphRepository.selectPreferredLocalWorkProjection(1L, 30L) } returns true

		val result = resolver.bindProjectionToEntity(1L, content(30L))

		assertEquals(WorkProjectionBindingAction.REUSED, result.success().action)
		coVerify(exactly = 1) { entityGraphRepository.selectPreferredLocalWorkProjection(1L, 30L) }
	}

	@Test
	fun `bindProjectionToEntity merges a source work with only the selected projection`() = runTest {
		val owners = mutableMapOf(30L to 2L)
		val localIds = mutableMapOf(
			1L to mutableSetOf(10L),
			2L to mutableSetOf(30L),
		)
		stubBindingState(owners, localIds)
		coEvery { entityGraphRepository.mergeEntities(1L, listOf(2L), 30L, true) } answers {
			owners[30L] = 1L
			localIds.getValue(1L).add(30L)
			localIds.remove(2L)
			1L
		}

		val result = resolver.bindProjectionToEntity(1L, content(30L))

		assertEquals(WorkProjectionBindingAction.MERGED_SINGLE_PROJECTION_WORK, result.success().action)
		coVerify(exactly = 1) { entityGraphRepository.mergeEntities(1L, listOf(2L), 30L, true) }
		coVerify(exactly = 0) { entityGraphRepository.moveLocalWorkProjectionToEntity(any(), any(), any(), any()) }
	}

	@Test
	fun `bindProjectionToEntity moves only the selected projection from a multi projection work`() = runTest {
		val owners = mutableMapOf(
			30L to 2L,
			31L to 2L,
		)
		val localIds = mutableMapOf(
			1L to mutableSetOf(10L),
			2L to mutableSetOf(30L, 31L),
		)
		stubBindingState(owners, localIds)
		coEvery { entityGraphRepository.moveLocalWorkProjectionToEntity(30L, 1L, 2L, true) } answers {
			owners[30L] = 1L
			localIds.getValue(2L).remove(30L)
			localIds.getValue(1L).add(30L)
			EntityGraphRepository.MoveLocalWorkProjectionResult(
				localMangaId = 30L,
				sourceEntityId = 2L,
				targetEntityId = 1L,
			)
		}

		val result = resolver.bindProjectionToEntity(1L, content(30L))

		assertEquals(WorkProjectionBindingAction.MOVED_PROJECTION, result.success().action)
		assertEquals(setOf(31L), localIds.getValue(2L))
		coVerify(exactly = 0) { entityGraphRepository.mergeEntities(any(), any(), any(), any()) }
	}

	private fun stubBindingState(
		owners: MutableMap<Long, Long>,
		localIds: MutableMap<Long, MutableSet<Long>>,
	) {
		coEvery { entityGraphRepository.getEntity(any()) } answers {
			val id = firstArg<Long>()
			domainEntity(id)
		}
		coEvery { dao.findEntity(any()) } answers {
			val id = firstArg<Long>()
			if (id in localIds) entity(id) else null
		}
		coEvery { dao.findActiveBindingsBySources(any(), any()) } answers {
			secondArg<List<String>>().mapNotNull { externalId ->
				val mangaId = externalId.toLong()
				owners[mangaId]?.let { owner -> binding(owner, "local_manga", mangaId) }
			}
		}
		coEvery { dao.findActiveLocalBindingsByEntities(any()) } answers {
			firstArg<List<Long>>().flatMap { entityId ->
				localIds[entityId].orEmpty().map { mangaId -> binding(entityId, "local_manga", mangaId) }
			}
		}
		coEvery { dao.findActiveLocalBindingsByEntity(any()) } answers {
			val entityId = firstArg<Long>()
			localIds[entityId].orEmpty().map { mangaId -> binding(entityId, "local_manga", mangaId) }
		}
		coEvery { dao.findEntityPrefsByIds(any()) } returns emptyList()
		coEvery { dao.findEntityPrefs(any()) } returns null
	}

	private fun WorkProjectionBindingResult.success(): WorkProjectionBindingResult.Success {
		return this as WorkProjectionBindingResult.Success
	}

	private fun entity(
		id: Long,
		type: EntityType = EntityType.WORK,
	): EntityRecord {
		return EntityRecord(
			id = id,
			type = type.name,
			primaryName = "Work $id",
			aliases = null,
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 0,
		)
	}

	private fun binding(
		entityId: Long,
		source: String,
		mangaId: Long,
	): EntityBindingRecord {
		return EntityBindingRecord(
			entityId = entityId,
			source = source,
			externalId = mangaId.toString(),
			confidence = 1f,
			isPrimary = true,
		)
	}

	private fun prefs(
		entityId: Long,
		preferredLocalMangaId: Long?,
	): EntityPrefsRecord {
		return EntityPrefsRecord(
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId,
			titleOverride = null,
			coverUrlOverride = null,
			contentRatingOverride = null,
			readingStatus = null,
			metadataSourceKind = null,
			metadataBindingSource = null,
			metadataBindingExternalId = null,
			metadataSourceService = null,
			metadataSourceRemoteId = null,
			updatedAt = 1L,
		)
	}

	private fun domainEntity(id: Long): Entity {
		return Entity(
			id = id,
			type = EntityType.WORK,
			contentType = ContentType.MANGA,
			primaryName = "Work $id",
			aliases = emptyList(),
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 0,
		)
	}

	private fun content(
		id: Long,
		title: String = "Work $id",
		contentType: ContentType = ContentType.MANGA,
	): Content {
		return Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = TestContentSource(contentType),
		)
	}

	private data class TestContentSource(
		override val contentType: ContentType,
	) : ContentSource {
		override val name: String = "TEST"
		override val locale: String = "en"
	}
}
