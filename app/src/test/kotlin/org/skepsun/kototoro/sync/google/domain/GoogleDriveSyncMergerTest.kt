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
import org.skepsun.kototoro.sync.google.data.model.SyncWorkHistory
import org.skepsun.kototoro.sync.google.data.model.SyncWorkState

class GoogleDriveSyncMergerTest {

	@Test
	fun `compact drops dirty favourite projections outside authoritative work anchors`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
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

	@Test
	fun `compact keeps current work sync protocol marker`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(20L, "Clean")),
				bindings = listOf(localBinding(entityId = 20L, mangaId = 2L)),
			),
			content = listOf(content(2L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				favourites = listOf(favourite(entityId = 20L, anchorMangaId = 2L)),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(GoogleDriveSyncSnapshot.SCHEMA_VERSION, compact.schemaVersion)
		assertEquals(GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2, compact.namespace)
		assertEquals(GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION, compact.semanticSchemaVersion)
	}

	@Test
	fun `compact merges work state by entity owner instead of projection anchor`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(20L, "Work")),
				bindings = listOf(
					localBinding(entityId = 20L, mangaId = 2L),
					localBinding(entityId = 20L, mangaId = 3L),
				),
				prefs = listOf(prefs(entityId = 20L, preferredLocalMangaId = 2L)),
			),
			content = listOf(content(2L), content(3L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(
					history(entityId = 20L, anchorMangaId = 3L, updatedAt = 10L),
					history(entityId = 20L, anchorMangaId = 2L, updatedAt = 20L),
				),
				favourites = listOf(
					favourite(entityId = 20L, anchorMangaId = 3L, updatedAt = 10L),
					favourite(entityId = 20L, anchorMangaId = 2L, updatedAt = 20L),
				),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.work.history.map { it.anchorMangaId })
		assertEquals(listOf(2L), compact.work.favourites.map { it.anchorMangaId })
		assertEquals(1, compact.work.history.size)
		assertEquals(1, compact.work.favourites.size)
	}

	@Test
	fun `compact does not merge projections by weak title and cover fallback`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(20L, "First"), entity(30L, "Second")),
				bindings = listOf(
					localBinding(entityId = 20L, mangaId = 2L),
					localBinding(entityId = 30L, mangaId = 3L),
				),
			),
			content = listOf(
				content(id = 2L, title = "Same", url = "", publicUrl = "", coverUrl = "same-cover"),
				content(id = 3L, title = "Same", url = "", publicUrl = "", coverUrl = "same-cover"),
			),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(
					history(entityId = 20L, anchorMangaId = 2L),
					history(entityId = 30L, anchorMangaId = 3L),
				),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L, 3L), compact.content.map { it.id })
		assertEquals(listOf(20L, 30L), compact.entityGraph.entities.map { it.id })
		assertEquals(listOf(2L, 3L), compact.work.history.map { it.anchorMangaId }.sorted())
	}

	@Test
	fun `compact merges same source url projection across legacy content ids`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(20L, "Work")),
				bindings = listOf(
					localBinding(entityId = 20L, mangaId = 2L),
					localBinding(entityId = 20L, mangaId = 99L),
				),
				prefs = listOf(prefs(entityId = 20L, preferredLocalMangaId = 99L)),
			),
			content = listOf(
				content(
					id = 2L,
					title = "Same Work",
					url = "/same",
					publicUrl = "https://public.example.test/same",
				),
				content(
					id = 99L,
					title = "Same Work",
					url = "/same",
					publicUrl = "https://public.example.test/same",
				),
			),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(history(entityId = 20L, anchorMangaId = 99L)),
				favourites = listOf(favourite(entityId = 20L, anchorMangaId = 99L)),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.content.map { it.id })
		assertEquals(listOf(2L), compact.entityGraph.bindings.mapNotNull { it.externalId.toLongOrNull() }.distinct())
		assertEquals(listOf(2L), compact.entityGraph.prefs.mapNotNull { it.preferredLocalMangaId })
		assertEquals(listOf(2L), compact.work.history.map { it.anchorMangaId })
		assertEquals(listOf(2L), compact.work.favourites.map { it.anchorMangaId })
	}

	@Test
	fun `compact merges same source public url projection when url is missing`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			entityGraph = SyncEntityGraph(
				entities = listOf(entity(20L, "Work")),
				bindings = listOf(
					localBinding(entityId = 20L, mangaId = 2L),
					localBinding(entityId = 20L, mangaId = 99L),
				),
			),
			content = listOf(
				content(
					id = 2L,
					title = "Same Work",
					url = "",
					publicUrl = "https://public.example.test/same",
				),
				content(
					id = 99L,
					title = "Same Work",
					url = "",
					publicUrl = "https://public.example.test/same",
				),
			),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(history(entityId = 20L, anchorMangaId = 99L)),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.content.map { it.id })
		assertEquals(listOf(2L), compact.work.history.map { it.anchorMangaId })
	}

	private fun content(
		id: Long,
		title: String = "Title $id",
		url: String = "https://example.test/$id",
		publicUrl: String = "https://public.example.test/$id",
		coverUrl: String = "https://cover.example.test/$id.jpg",
	): SyncContent {
		return SyncContent(
			id = id,
			title = title,
			url = url,
			publicUrl = publicUrl,
			rating = 0f,
			isNsfw = false,
			coverUrl = coverUrl,
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

	private fun history(entityId: Long, anchorMangaId: Long, updatedAt: Long = 1L): SyncWorkHistory {
		return SyncWorkHistory(
			entityId = entityId,
			anchorMangaId = anchorMangaId,
			createdAt = 1L,
			updatedAt = updatedAt,
		)
	}

	private fun favourite(entityId: Long, anchorMangaId: Long?, updatedAt: Long = 1L): SyncWorkFavourite {
		return SyncWorkFavourite(
			entityId = entityId,
			categoryId = 1L,
			anchorMangaId = anchorMangaId,
			sortKey = 1,
			isPinned = false,
			createdAt = 1L,
			updatedAt = updatedAt,
			deletedAt = 0L,
		)
	}
}
