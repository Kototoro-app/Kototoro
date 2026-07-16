package org.skepsun.kototoro.sync.google.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash

class GoogleDriveSyncEntityRestoreTest {

	private val dao = mockk<EntityGraphDao>(relaxed = true)
	private val db = mockk<MangaDatabase> {
		every { getEntityGraphDao() } returns dao
	}

	@Test
	fun `restore maps to existing hash owner instead of updating id match into unique conflict`() = runTest {
		val staleIdMatch = entity(id = 1L, primaryName = "Old remote title")
		val hashOwner = entity(id = 2L, primaryName = "Frieren")
		val remote = entity(
			id = 1L,
			primaryName = "Frieren",
			createdAt = 5L,
			lastAccessed = 20L,
			accessCount = 7,
		)

		coEvery { dao.findEntity(1L) } returns staleIdMatch
		coEvery {
			dao.findEntityByTypeAndNameHashAndContentType("WORK", computeNameHash("Frieren"), null)
		} returns hashOwner

		val localId = db.restoreGoogleDriveSyncEntity(remote)

		assertEquals(2L, localId)
		coVerify(exactly = 1) {
			dao.upsertEntityRecord(
				match {
					it.id == 2L &&
						it.primaryName == "Frieren" &&
						it.nameHash == computeNameHash("Frieren") &&
						it.lastAccessed == 20L &&
						it.accessCount == 7
				},
			)
		}
	}

	@Test
	fun `restore maps new remote entity to existing hash owner instead of inserting fallback identity`() = runTest {
		val hashOwner = entity(id = 2L, primaryName = "Frieren")
		val remote = entity(
			id = 100L,
			primaryName = "Frieren",
			createdAt = 5L,
			lastAccessed = 20L,
			accessCount = 7,
		)

		coEvery { dao.findEntity(100L) } returns null
		coEvery {
			dao.findEntityByTypeAndNameHashAndContentType("WORK", computeNameHash("Frieren"), null)
		} returns hashOwner

		val localId = db.restoreGoogleDriveSyncEntity(remote)

		assertEquals(2L, localId)
		coVerify(exactly = 0) { dao.insertEntity(any()) }
		coVerify(exactly = 0) { dao.insertEntityIgnore(any()) }
		coVerify(exactly = 1) {
			dao.upsertEntityRecord(
				match {
					it.id == 2L &&
						it.primaryName == "Frieren" &&
						it.nameHash == computeNameHash("Frieren")
				},
			)
		}
	}

	@Test
	fun `restore isolates incompatible sync id content type`() = runTest {
		val localManga = entity(
			id = 2L,
			primaryName = "Frieren",
			contentType = "MANGA",
			syncId = "shared-sync-id",
		)
		val remoteVideo = entity(
			id = 100L,
			primaryName = "Frieren",
			contentType = "VIDEO",
			syncId = "shared-sync-id",
		)

		coEvery { dao.findEntityBySyncId("shared-sync-id") } returns localManga
		coEvery { dao.findEntity(100L) } returns null
		coEvery {
			dao.findEntityByTypeAndNameHashAndContentType("WORK", computeNameHash("Frieren"), "VIDEO")
		} returns null
		coEvery {
			dao.insertEntityIgnore(match { it.contentType == "VIDEO" && it.syncId != "shared-sync-id" })
		} returns 50L

		assertEquals(50L, db.restoreGoogleDriveSyncEntity(remoteVideo))
	}

	private fun entity(
		id: Long,
		primaryName: String,
		createdAt: Long = 10L,
		lastAccessed: Long = 10L,
		accessCount: Int = 1,
		contentType: String? = null,
		syncId: String = "",
	): EntityRecord {
		return EntityRecord(
			id = id,
			type = "WORK",
			contentType = contentType,
			syncId = syncId,
			primaryName = primaryName,
			nameHash = computeNameHash(primaryName),
			aliases = null,
			createdAt = createdAt,
			lastAccessed = lastAccessed,
			accessCount = accessCount,
		)
	}
}
