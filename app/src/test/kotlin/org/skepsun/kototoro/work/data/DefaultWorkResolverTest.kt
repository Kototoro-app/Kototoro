package org.skepsun.kototoro.work.data

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.work.domain.WorkMigrationState

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
}
