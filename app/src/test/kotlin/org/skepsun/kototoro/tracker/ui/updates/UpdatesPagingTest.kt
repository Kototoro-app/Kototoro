package org.skepsun.kototoro.tracker.ui.updates

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.paging.LargeLibraryPagingConfig
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import java.time.Instant

class UpdatesPagingTest {

	// ---- date separator presentation -------------------------------------
	@Test
	fun `grouped presentation inserts one header per date bucket change`() = runTest {
		val snapshot = flowOf(
			PagingData.from<ListModel>(
				listOf(
					TestItem(1, "today"),
					TestItem(2, "today"),
					TestItem(3, "yesterday"),
				),
			),
		).map { pagingData ->
			pagingData.applyUpdatesPagingPresentation(grouped = true) { item ->
				(item as? TestItem)?.let { ListHeader(it.headerKey) }
			}
		}.asSnapshot()

		assertEquals(
			listOf(
				ListHeader("today"),
				TestItem(1, "today"),
				TestItem(2, "today"),
				ListHeader("yesterday"),
				TestItem(3, "yesterday"),
			),
			snapshot,
		)
	}

	@Test
	fun `separator is not duplicated when the same date bucket spans the page boundary`() = runTest {
		val snapshot = flowOf(
			PagingData.from<ListModel>(
				listOf(
					TestItem(1, "today"),
					TestItem(2, "yesterday"),
					TestItem(3, "yesterday"),
					TestItem(4, "days_ago"),
				),
			),
		).map { pagingData ->
			pagingData.applyUpdatesPagingPresentation(grouped = true) { item ->
				(item as? TestItem)?.let { ListHeader(it.headerKey) }
			}
		}.asSnapshot()

		assertEquals(
			listOf(
				ListHeader("today"),
				TestItem(1, "today"),
				ListHeader("yesterday"),
				TestItem(2, "yesterday"),
				TestItem(3, "yesterday"),
				ListHeader("days_ago"),
				TestItem(4, "days_ago"),
			),
			snapshot,
		)
	}

	@Test
	fun `ungrouped presentation passes items through unchanged`() = runTest {
		val items = listOf(TestItem(1, "today"), TestItem(2, "yesterday"))
		val snapshot = flowOf(PagingData.from<ListModel>(items))
			.map { pagingData ->
				pagingData.applyUpdatesPagingPresentation(grouped = false) { item ->
					(item as? TestItem)?.let { ListHeader(it.headerKey) }
				}
			}
			.asSnapshot()

		assertEquals(items, snapshot)
		assertTrue(snapshot.none { it is ListHeader })
	}

	// ---- entity grouping across paging batches ---------------------------
	@Test
	fun `first load groups every row of the same entity into one update group`() {
		val projectionA = tracking(
			mangaId = 11L,
			entityId = 1L,
			newChapters = 2,
			lastChapterDate = Instant.parse("2026-08-18T10:00:00Z"),
		)
		val projectionB = tracking(
			mangaId = 12L,
			entityId = 1L,
			newChapters = 3,
			lastChapterDate = Instant.parse("2026-08-19T10:00:00Z"),
		)
		val otherEntity = tracking(
			mangaId = 21L,
			entityId = 2L,
			newChapters = 5,
			lastChapterDate = Instant.parse("2026-08-17T10:00:00Z"),
		)

		val groups = listOf(projectionA, projectionB, otherEntity)
			.groupTrackingByEntity(emptyMap(), emptyMap())

		assertEquals(2, groups.size)
		assertTrue(groups.all { it.uiId != 0L })

		val entityOne = groups.first { it.entityId == 1L }
		assertEquals(setOf(11L, 12L), entityOne.mangaIds)
		assertEquals(5, entityOne.totalNewChapters)
		assertEquals(Instant.parse("2026-08-19T10:00:00Z"), entityOne.lastChapterDate)

		val entityTwo = groups.first { it.entityId == 2L }
		assertEquals(setOf(21L), entityTwo.mangaIds)
		assertEquals(5, entityTwo.totalNewChapters)
		assertNotEquals(entityOne.uiId, entityTwo.uiId)
	}

	@Test
	fun `append page for an already loaded entity maps to the same unique ui id`() {
		val firstPage = listOf(
			tracking(mangaId = 11L, entityId = 1L, newChapters = 2),
			tracking(mangaId = 21L, entityId = 2L, newChapters = 4),
		)
		val secondPage = listOf(
			tracking(mangaId = 12L, entityId = 1L, newChapters = 3),
		)

		val firstGroups = firstPage.groupTrackingByEntity(emptyMap(), emptyMap())
		val secondGroups = secondPage.groupTrackingByEntity(emptyMap(), emptyMap())

		assertEquals(2, firstGroups.size)
		assertEquals(1, secondGroups.size)
		assertEquals(firstGroups.first { it.entityId == 1L }.uiId, secondGroups.single().uiId)
		assertEquals(
			firstGroups.map { it.entityId }.toSet() + secondGroups.map { it.entityId }.toSet(),
			setOf(1L, 2L),
		)
	}

	@Test
	fun `empty page produces no groups`() {
		assertTrue(emptyList<ContentTracking>().groupTrackingByEntity(emptyMap(), emptyMap()).isEmpty())
	}

	@Test
	fun `filtered empty result presents empty paging data without headers`() = runTest {
		// A filter that matches nothing at the SQL layer produces zero rows for
		// the page mapping. The presentation must yield an empty snapshot (the
		// screen then shows its empty state) and must not synthesize headers.
		val pager = Pager(
			config = LargeLibraryPagingConfig,
			pagingSourceFactory = {
				object : PagingSource<Int, ListModel>() {
					override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ListModel> =
						LoadResult.Page(emptyList(), prevKey = null, nextKey = null, itemsBefore = 0, itemsAfter = 0)

					override fun getRefreshKey(state: PagingState<Int, ListModel>): Int? = null
				}
			},
		).flow.map { pagingData ->
			pagingData.applyUpdatesPagingPresentation(grouped = true) { ListHeader("今天") }
		}

		val snapshot = pager.asSnapshot()
		assertTrue(snapshot.isEmpty())
	}

	@Test
	fun `ui group id encodes content type making mixed types never collide`() {
		val entityId = 42L
		val manga = entityId.toUiGroupId(ContentType.MANGA.ordinal)
		val novel = entityId.toUiGroupId(ContentType.NOVEL.ordinal)

		assertNotEquals(manga, novel)
		// Stable for the same entity + type, and always in the negative id space
		// so grouped rows never collide with plain manga rows (positive ids).
		assertEquals(manga, entityId.toUiGroupId(ContentType.MANGA.ordinal))
		assertTrue(manga < 0)
		assertTrue(novel < 0)
	}

	private data class TestItem(val id: Long, val headerKey: String) : ListModel {
		override fun areItemsTheSame(other: ListModel): Boolean = other is TestItem && other.id == id
	}

	private fun tracking(
		mangaId: Long,
		entityId: Long?,
		newChapters: Int,
		lastChapterDate: Instant? = Instant.parse("2026-08-19T10:00:00Z"),
		contentType: ContentType = ContentType.MANGA,
	): ContentTracking {
		return ContentTracking(
			anchorMangaId = mangaId,
			entityId = entityId,
			preferredLocalMangaId = if (entityId == null) mangaId else null,
			manga = content(mangaId, contentType),
			lastChapterId = mangaId * 100,
			lastCheck = lastChapterDate,
			lastChapterDate = lastChapterDate,
			newChapters = newChapters,
		)
	}

	private fun content(id: Long, contentType: ContentType): Content {
		return Content(
			id = id,
			title = "Work " + id,
			altTitles = emptySet(),
			url = "/" + id,
			publicUrl = "https://example.org/" + id,
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = TrackingTestSource(contentType),
		)
	}

	private class TrackingTestSource(
		override val contentType: ContentType,
	) : ContentSource {
		override val name: String = "test"
		override val locale: String = "en"
	}
}
