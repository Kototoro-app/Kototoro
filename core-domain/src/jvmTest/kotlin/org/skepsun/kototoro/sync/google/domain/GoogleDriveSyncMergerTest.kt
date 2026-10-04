package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncContent
import org.skepsun.kototoro.sync.google.data.model.SyncConfig
import org.skepsun.kototoro.sync.google.data.model.SyncFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncFavouriteCategory
import org.skepsun.kototoro.sync.google.data.model.SyncFeedState
import org.skepsun.kototoro.sync.google.data.model.SyncHistory
import org.skepsun.kototoro.sync.google.data.model.SyncTrackLog
import org.skepsun.kototoro.sync.google.data.model.SyncTrack
import org.skepsun.kototoro.sync.google.data.model.SyncWorkFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncWorkHistory
import org.skepsun.kototoro.sync.google.data.model.SyncWorkState

class GoogleDriveSyncMergerTest {

	@Test
	fun `compact writes the content v3 protocol marker for legacy work snapshots`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			content = listOf(content(2L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				favourites = listOf(workFavourite(entityId = 20L, anchorMangaId = 2L)),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(GoogleDriveSyncSnapshot.SCHEMA_VERSION, compact.schemaVersion)
		assertEquals(GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3, compact.namespace)
		assertEquals(GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION, compact.semanticSchemaVersion)
	}

	@Test
	fun `legacy work state lands on its anchor content`() {
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			content = listOf(content(2L), content(3L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(
					workHistory(entityId = 20L, anchorMangaId = 3L, updatedAt = 10L),
					workHistory(entityId = 30L, anchorMangaId = 2L, updatedAt = 20L),
				),
				favourites = listOf(
					workFavourite(entityId = 20L, anchorMangaId = 3L),
					// No anchor: the entity-era row has no content to land on.
					workFavourite(entityId = 40L, anchorMangaId = null),
				),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L, 3L), compact.history.map { it.mangaId })
		assertEquals(listOf(3L), compact.favourites.map { it.mangaId })
		assertEquals(listOf(1L), compact.categories.map { it.id })
	}

	@Test
	fun `legacy work state keeps zero and negative anchor ids`() {
		// Local and imported manga use negative ids and 0 is a valid id too; only a missing
		// (null) favourite anchor means there is nothing to land on.
		val snapshot = GoogleDriveSyncSnapshot(
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			content = listOf(content(-7L), content(0L)),
			work = SyncWorkState(
				categories = listOf(category(1L)),
				history = listOf(
					workHistory(entityId = 20L, anchorMangaId = -7L, updatedAt = 20L),
					workHistory(entityId = 30L, anchorMangaId = 0L, updatedAt = 10L),
				),
				favourites = listOf(workFavourite(entityId = 20L, anchorMangaId = -7L)),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(-7L, 0L), compact.history.map { it.mangaId })
		assertEquals(listOf(-7L), compact.favourites.map { it.mangaId })
	}

	@Test
	fun `compact does not merge contents by weak title and cover fallback`() {
		val snapshot = snapshot(
			content = listOf(
				content(id = 2L, title = "Same", url = "", publicUrl = "", coverUrl = "same-cover"),
				content(id = 3L, title = "Same", url = "", publicUrl = "", coverUrl = "same-cover"),
			),
			history = listOf(history(mangaId = 2L), history(mangaId = 3L)),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L, 3L), compact.content.map { it.id })
		assertEquals(listOf(2L, 3L), compact.history.map { it.mangaId }.sorted())
	}

	@Test
	fun `compact merges same source url content across legacy content ids`() {
		val snapshot = snapshot(
			content = listOf(
				content(id = 2L, url = "/same", publicUrl = "https://public.example.test/same"),
				content(id = 99L, url = "/same", publicUrl = "https://public.example.test/same"),
			),
			history = listOf(history(mangaId = 99L)),
			favourites = listOf(favourite(mangaId = 99L)),
			logs = listOf(SyncTrackLog(mangaId = 99L, chapters = "Ch. 1", createdAt = 5L, isUnread = true)),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.content.map { it.id })
		assertEquals(listOf(2L), compact.history.map { it.mangaId })
		assertEquals(listOf(2L), compact.favourites.map { it.mangaId })
		assertEquals(listOf(2L), compact.feed.logs.map { it.mangaId })
	}

	@Test
	fun `compact merges same source public url content when url is missing`() {
		val snapshot = snapshot(
			content = listOf(
				content(id = 2L, url = "", publicUrl = "https://public.example.test/same"),
				content(id = 99L, url = "", publicUrl = "https://public.example.test/same"),
			),
			history = listOf(history(mangaId = 99L)),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L), compact.content.map { it.id })
		assertEquals(listOf(2L), compact.history.map { it.mangaId })
	}

	@Test
	fun `compact keeps same url from different sources apart`() {
		val snapshot = snapshot(
			content = listOf(
				content(id = 2L, url = "/same", source = "alpha"),
				content(id = 3L, url = "/same", source = "beta"),
			),
		)

		val compact = GoogleDriveSyncMerger.combine(listOf(snapshot))!!

		assertEquals(listOf(2L, 3L), compact.content.map { it.id })
	}

	@Test
	fun `mergeSnapshots keeps newest state when local and remote carry the same content under different ids`() {
		val local = snapshot(
			content = listOf(content(id = 1L, url = "https://mangadex.org/title/123")),
			history = listOf(history(mangaId = 1L, updatedAt = 10L)),
			favourites = listOf(favourite(mangaId = 1L, updatedAt = 10L)),
		)
		val remote = snapshot(
			content = listOf(content(id = 2L, url = "https://mangadex.org/title/123")),
			history = listOf(history(mangaId = 2L, updatedAt = 20L)),
			favourites = listOf(favourite(mangaId = 2L, updatedAt = 20L)),
		)

		val merged = GoogleDriveSyncMerger.mergeSnapshots(local, remote)

		assertEquals(listOf(1L), merged.content.map { it.id })
		assertEquals(listOf(1L to 20L), merged.history.map { it.mangaId to it.updatedAt })
		assertEquals(listOf(1L to 20L), merged.favourites.map { it.mangaId to it.updatedAt })
	}

	@Test
	fun `newer deletion tombstones replace active history and favourites in either merge direction`() {
		val active = snapshot(
			content = listOf(content(1)),
			history = listOf(history(1, updatedAt = 10)),
			favourites = listOf(favourite(1, updatedAt = 10)),
		)
		val deleted = snapshot(
			content = listOf(content(1)),
			history = listOf(SyncHistory(mangaId = 1, createdAt = 1, updatedAt = 20, deletedAt = 20)),
			favourites = listOf(SyncFavourite(mangaId = 1, categoryId = 1, updatedAt = 20, deletedAt = 20)),
		)
		listOf(active to deleted, deleted to active).forEach { (local, remote) ->
			val merged = GoogleDriveSyncMerger.mergeSnapshots(local, remote)
			assertEquals(20L, merged.history.single().deletedAt)
			assertEquals(20L, merged.favourites.single().deletedAt)
		}
	}

	@Test
	fun `newer update state keeps its cleared count rather than resurrecting older unread chapters`() {
		val older = GoogleDriveSyncSnapshot(feed = SyncFeedState(tracks = listOf(
			SyncTrack(mangaId = 1, newChapters = 8, lastChapterDate = 10, lastCheckTime = 100),
		)))
		val newer = GoogleDriveSyncSnapshot(feed = SyncFeedState(tracks = listOf(
			SyncTrack(mangaId = 1, newChapters = 0, lastChapterDate = 20, lastCheckTime = 50),
		)))
		val merged = GoogleDriveSyncMerger.mergeSnapshots(older, newer)
		assertEquals(0, merged.feed.tracks.single().newChapters)
		assertEquals(20L, merged.feed.tracks.single().lastChapterDate)
	}

	@Test
	fun `settings merge picks the newer revision and retains local settings on equal revisions`() {
		fun configSnapshot(revision: Long, value: String) = GoogleDriveSyncSnapshot(
			config = SyncConfig(revision = revision, settings = mapOf("theme" to value)),
		)
		val local = configSnapshot(10, "local")
		assertEquals("remote", GoogleDriveSyncMerger.mergeSnapshots(local, configSnapshot(11, "remote"))
			.config!!.settings["theme"])
		assertEquals("local", GoogleDriveSyncMerger.mergeSnapshots(local, configSnapshot(10, "remote"))
			.config!!.settings["theme"])
	}

	private fun snapshot(
		content: List<SyncContent>,
		history: List<SyncHistory> = emptyList(),
		favourites: List<SyncFavourite> = emptyList(),
		logs: List<SyncTrackLog> = emptyList(),
	) = GoogleDriveSyncSnapshot(
		namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
		semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
		content = content,
		categories = listOf(category(1L)),
		history = history,
		favourites = favourites,
		feed = SyncFeedState(tracks = emptyList(), logs = logs),
	)

	private fun content(
		id: Long,
		title: String = "Title $id",
		url: String = "https://example.test/$id",
		publicUrl: String = "https://public.example.test/$id",
		coverUrl: String = "https://cover.example.test/$id.jpg",
		source: String = "source",
	): SyncContent {
		return SyncContent(
			id = id,
			title = title,
			url = url,
			publicUrl = publicUrl,
			rating = 0f,
			isNsfw = false,
			coverUrl = coverUrl,
			source = source,
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

	private fun history(mangaId: Long, updatedAt: Long = 1L) = SyncHistory(
		mangaId = mangaId,
		createdAt = 1L,
		updatedAt = updatedAt,
	)

	private fun favourite(mangaId: Long, updatedAt: Long = 1L) = SyncFavourite(
		mangaId = mangaId,
		categoryId = 1L,
		createdAt = 1L,
		updatedAt = updatedAt,
	)

	private fun workHistory(entityId: Long, anchorMangaId: Long, updatedAt: Long = 1L): SyncWorkHistory {
		return SyncWorkHistory(
			entityId = entityId,
			anchorMangaId = anchorMangaId,
			createdAt = 1L,
			updatedAt = updatedAt,
		)
	}

	private fun workFavourite(entityId: Long, anchorMangaId: Long?, updatedAt: Long = 1L): SyncWorkFavourite {
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
