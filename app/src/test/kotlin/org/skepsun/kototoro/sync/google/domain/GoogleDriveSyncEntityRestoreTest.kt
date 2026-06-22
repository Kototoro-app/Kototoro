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
		coEvery { dao.findEntityByTypeAndNameHash("WORK", computeNameHash("Frieren")) } returns hashOwner

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

	private fun entity(
		id: Long,
		primaryName: String,
		createdAt: Long = 10L,
		lastAccessed: Long = 10L,
		accessCount: Int = 1,
	): EntityRecord {
		return EntityRecord(
			id = id,
			type = "WORK",
			primaryName = primaryName,
			nameHash = computeNameHash(primaryName),
			aliases = null,
			createdAt = createdAt,
			lastAccessed = lastAccessed,
			accessCount = accessCount,
		)
	}
}
