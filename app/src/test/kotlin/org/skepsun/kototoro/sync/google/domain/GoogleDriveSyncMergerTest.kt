package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncContent
import org.skepsun.kototoro.sync.google.data.model.SyncEntityBindingRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityGraph
import org.skepsun.kototoro.sync.google.data.model.SyncEntityPrefsRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRelationRecord
import org.skepsun.kototoro.sync.google.data.model.SyncFavouriteCategory
import org.skepsun.kototoro.sync.google.data.model.SyncWorkFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncWorkState

class GoogleDriveSyncMergerTest {

	@Test
	fun `compact drops dirty favourite projections outside authoritative work anchors`() {
		val snapshot = GoogleDriveSyncSnapshot(
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(10L, "Dirty"), entity(20L, "Clean")),
				bindings = listOf(
					localBinding(entityId = 10L, mangaId = 1L),
					localBinding(entityId = 20L, mangaId = 2L),
					localBinding(entityId = 20L, mangaId = 3L),
				),
				relations = listOf(
					SyncEntityRelationRecord(
						fromEntityId = 10L,
						toEntityId = 20L,
						type = "related",
						createdAt = 1L,
					),
				),
				prefs = listOf(
					prefs(entityId = 10L, preferredLocalMangaId = 1L),
					prefs(entityId = 20L, preferredLocalMangaId = 2L),
				),
			),
			content = listOf(content(1L), content(2L), content(3L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				favourites = listOf(
					favourite(entityId = 10L, anchorMangaId = null),
					favourite(entityId = 20L, anchorMangaId = 2L),
				),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.content.map { it.id })
		assertEquals(listOf(20L), compact.entityGraph.entities.map { it.id })
		assertEquals(listOf(2L), compact.entityGraph.bindings.mapNotNull { it.externalId.toLongOrNull() })
		assertEquals(listOf(20L), compact.entityGraph.prefs.map { it.entityId })
		assertTrue(compact.entityGraph.relations.isEmpty())
		assertEquals(listOf(2L), compact.work.favourites.map { it.anchorMangaId })
	}

	private fun content(id: Long): SyncContent {
		return SyncContent(
			id = id,
			title = "Title $id",
			url = "https://example.test/$id",
			publicUrl = "https://public.example.test/$id",
			rating = 0f,
			isNsfw = false,
			coverUrl = "https://cover.example.test/$id.jpg",
			source = "source",
		)
	}

	private fun entity(id: Long, name: String): SyncEntityRecord {
		return SyncEntityRecord(
			id = id,
			type = "WORK",
			primaryName = name,
			nameHash = id,
			createdAt = 1L,
			lastAccessed = 1L,
			accessCount = 1,
		)
	}

	private fun localBinding(entityId: Long, mangaId: Long): SyncEntityBindingRecord {
		return SyncEntityBindingRecord(
			entityId = entityId,
			source = "local_manga",
			externalId = mangaId.toString(),
			sourceKind = "LOCAL_MANGA",
			state = "LEGACY",
			createdBy = "SYNC",
			isPrimary = false,
			updatedAt = 1L,
		)
	}

	private fun prefs(entityId: Long, preferredLocalMangaId: Long): SyncEntityPrefsRecord {
		return SyncEntityPrefsRecord(
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId,
			metadataBindingSource = null,
			metadataBindingExternalId = null,
			updatedAt = 1L,
		)
	}

	private fun category(id: Long): SyncFavouriteCategory {
		return SyncFavouriteCategory(
			id = id,
			createdAt = 1L,
			sortKey = 1,
			title = "Default",
			order = "",
			track = false,
			isVisibleInLibrary = true,
		)
	}

	private fun favourite(entityId: Long, anchorMangaId: Long?): SyncWorkFavourite {
		return SyncWorkFavourite(
			entityId = entityId,
			categoryId = 1L,
			anchorMangaId = anchorMangaId,
			sortKey = 1,
			isPinned = false,
			createdAt = 1L,
			updatedAt = 1L,
			deletedAt = 0L,
		)
	}
}
