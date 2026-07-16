package org.skepsun.kototoro.work.domain

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_DETACH
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_TABLE
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_ACTION_VERSION
import org.skepsun.kototoro.entitygraph.data.WORK_PROJECTION_IDENTITY_STATUS_ACTIVE
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.work.data.WorkMigrationLedgerDao
import org.skepsun.kototoro.work.data.WorkMigrationLedgerEntity

class WorkDuplicateCandidateRepositoryTest {

	private val entityDao = mockk<EntityGraphDao>(relaxed = true)
	private val mangaDao = mockk<MangaDao>(relaxed = true)
	private val ledgerDao = mockk<WorkMigrationLedgerDao>(relaxed = true)
	private val db = mockk<MangaDatabase> {
		every { getEntityGraphDao() } returns entityDao
		every { getMangaDao() } returns mangaDao
		every { getWorkMigrationLedgerDao() } returns ledgerDao
	}
	private val workResolver = mockk<WorkResolver>()
	private val repository = WorkDuplicateCandidateRepository(db, workResolver)

	@Test
	fun `findCandidates returns title match for another work with same normalized title`() = runTest {
		val content = content(id = 10L, title = "One Piece")
		coEvery { workResolver.resolveByMangaId(10L) } returns reviewIdentity(10L)
		coEvery {
			entityDao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, listOf(computeNameHash("One Piece")))
		} returns listOf(entity(id = 2L, title = "One Piece"))
		coEvery { entityDao.findActiveLocalBindingsByEntity(2L) } returns listOf(binding(2L, 20L))
		coEvery { mangaDao.find(20L) } returns mangaWithTags(id = 20L, title = "One Piece", source = "mangaDex")

		val candidates = repository.findCandidates(content)

		assertEquals(1, candidates.size)
		assertEquals(2L, candidates.single().entityId)
		assertEquals(WorkDuplicateCandidateReason.TITLE_MATCH, candidates.single().reason)
		assertEquals(2L, candidates.single().mergeBackTargetEntityId)
		assertEquals(listOf("mangaDex"), candidates.single().sourceLabels)
	}

	@Test
	fun `findCandidates excludes current resolved work`() = runTest {
		val content = content(id = 10L, title = "One Piece")
		coEvery { workResolver.resolveByMangaId(10L) } returns identity(entityId = 2L, mangaId = 10L)
		coEvery {
			entityDao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, listOf(computeNameHash("One Piece")))
		} returns listOf(entity(id = 2L, title = "One Piece"))

		assertEquals(emptyList<WorkDuplicateCandidate>(), repository.findCandidates(content))
	}

	@Test
	fun `findCandidates sorts same projection before title-only matches`() = runTest {
		val content = content(
			id = 10L,
			title = "One Piece",
			source = "sourceA",
			url = "/one-piece",
			publicUrl = "https://example.org/one-piece",
		)
		coEvery { workResolver.resolveByMangaId(10L) } returns reviewIdentity(10L)
		coEvery {
			entityDao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, listOf(computeNameHash("One Piece")))
		} returns listOf(
			entity(id = 2L, title = "One Piece"),
			entity(id = 3L, title = "One Piece"),
		)
		coEvery { entityDao.findActiveLocalBindingsByEntity(2L) } returns listOf(binding(2L, 20L))
		coEvery { entityDao.findActiveLocalBindingsByEntity(3L) } returns listOf(binding(3L, 30L))
		coEvery { mangaDao.find(20L) } returns mangaWithTags(
			id = 20L,
			title = "One Piece",
			source = "sourceB",
			url = "/other",
			publicUrl = "https://other.example.org/one-piece",
		)
		coEvery { mangaDao.find(30L) } returns mangaWithTags(
			id = 30L,
			title = "One Piece",
			source = "sourceA",
			url = "/one-piece",
			publicUrl = "https://example.org/one-piece",
		)

		val candidates = repository.findCandidates(content)

		assertEquals(listOf(3L, 2L), candidates.map { it.entityId })
		assertEquals(WorkDuplicateCandidateReason.SAME_PROJECTION, candidates.first().reason)
		assertEquals(3L, candidates.first().mergeBackTargetEntityId)
	}

	@Test
	fun `findCandidates returns merge-back candidate for previously detached projection`() = runTest {
		val content = content(id = 10L, title = "One Piece")
		coEvery { workResolver.resolveByMangaId(10L) } returns identity(entityId = 3L, mangaId = 10L)
		coEvery { ledgerDao.findLatest(WORK_PROJECTION_IDENTITY_ACTION_TABLE, "10") } returns WorkMigrationLedgerEntity(
			legacyTable = WORK_PROJECTION_IDENTITY_ACTION_TABLE,
			legacyKey = "10",
			legacyChecksum = "2:3:$WORK_PROJECTION_IDENTITY_ACTION_DETACH",
			targetEntityId = 3L,
			migrationVersion = WORK_PROJECTION_IDENTITY_ACTION_VERSION,
			status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
			migratedAt = 1L,
		)
		coEvery { entityDao.findEntity(2L) } returns entity(id = 2L, title = "One Piece")
		coEvery { entityDao.findActiveLocalBindingsByEntity(2L) } returns listOf(binding(2L, 20L))
		coEvery {
			entityDao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, listOf(computeNameHash("One Piece")))
		} returns emptyList()

		val candidates = repository.findCandidates(content)

		assertEquals(1, candidates.size)
		assertEquals(2L, candidates.single().entityId)
		assertEquals(2L, candidates.single().mergeBackTargetEntityId)
		assertEquals(WorkDuplicateCandidateReason.PREVIOUSLY_DETACHED, candidates.single().reason)
	}

	private fun reviewIdentity(mangaId: Long): WorkIdentity {
		return WorkIdentity(
			entityId = null,
			requestedMangaId = mangaId,
			preferredMangaId = null,
			localMangaIds = emptySet(),
			migrationState = WorkMigrationState.NEEDS_REVIEW,
		)
	}

	private fun identity(entityId: Long, mangaId: Long): WorkIdentity {
		return WorkIdentity(
			entityId = entityId,
			requestedMangaId = mangaId,
			preferredMangaId = mangaId,
			localMangaIds = setOf(mangaId),
			migrationState = WorkMigrationState.VALID,
		)
	}

	private fun entity(id: Long, title: String): EntityRecord {
		return EntityRecord(
			id = id,
			type = EntityType.WORK.name,
			contentType = ContentType.MANGA.name,
			primaryName = title,
			nameHash = computeNameHash(title),
			aliases = null,
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 0,
		)
	}

	private fun binding(entityId: Long, mangaId: Long): EntityBindingRecord {
		return EntityBindingRecord(
			entityId = entityId,
			source = "local_manga",
			externalId = mangaId.toString(),
			confidence = 1f,
			isPrimary = true,
		)
	}

	private fun mangaWithTags(
		id: Long,
		title: String,
		source: String,
		url: String = "/$id",
		publicUrl: String = "https://example.org/$id",
	): MangaWithTags {
		return MangaWithTags(
			manga = MangaEntity(
				id = id,
				title = title,
				altTitles = null,
				url = url,
				publicUrl = publicUrl,
				rating = RATING_UNKNOWN,
				isNsfw = false,
				contentRating = null,
				coverUrl = "",
				largeCoverUrl = null,
				state = null,
				authors = null,
				source = source,
			),
			tags = emptyList(),
		)
	}

	private fun content(
		id: Long,
		title: String,
		source: String = "sourceA",
		url: String = "/$id",
		publicUrl: String = "https://example.org/$id",
	): Content {
		return Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = testSource(source),
		)
	}

	private fun testSource(name: String): ContentSource {
		return object : ContentSource {
			override val name: String = name
			override val locale: String = ""
			override val contentType: ContentType = ContentType.MANGA
		}
	}
}
