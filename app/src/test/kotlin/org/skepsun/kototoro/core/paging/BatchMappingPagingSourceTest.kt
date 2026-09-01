package org.skepsun.kototoro.core.paging

import androidx.paging.Pager
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchMappingPagingSourceTest {

	@Test
	fun `6500 favourites load only first 64 then append unique entities`() = runTest {
		assertPagedDataset(size = 6_500)
	}

	@Test
	fun `3200 history entries load only first 64 then append unique entities`() = runTest {
		assertPagedDataset(size = 3_200)
	}

	@Test
	fun `filtered pages keep scanning without rebuilding earlier pages`() = runTest {
		val delegate = RecordingEntityPagingSource((1L..320L).toList())
		val source = BatchMappingPagingSource(delegate) { page -> page.filter { it > 128L } }
		val first = source.load(
			PagingSource.LoadParams.Refresh(null, LargeLibraryPagingConfig.initialLoadSize, false),
		) as PagingSource.LoadResult.Page
		assertEquals((129L..192L).toList(), first.data)
		assertEquals(listOf(64, 64, 64), delegate.requestedLoadSizes)
	}

	@Test
	fun `large library paging config stays bounded`() {
		assertEquals(64, LargeLibraryPagingConfig.pageSize)
		assertEquals(64, LargeLibraryPagingConfig.initialLoadSize)
		assertEquals(24, LargeLibraryPagingConfig.prefetchDistance)
		assertEquals(false, LargeLibraryPagingConfig.enablePlaceholders)
	}

	@Test
	fun `filtered paging never washes out already loaded rows as later pages land`() = runTest {
		// Mirrors a large favourites library (5000+) with an active quick filter
		// (e.g. Downloaded / SFW) that drops half of every raw page: the user sits
		// at the top while the prefetch window loads more pages in the background,
		// and the already-loaded screen must stay exactly the start of the
		// filtered order instead of being replaced by other rows.
		val delegate = RecordingEntityPagingSource((1L..4_900L).toList())
		val source = BatchMappingPagingSource(delegate, diagnosticLabel = "repro") { raw ->
			raw.filter { it % 2L == 0L }
		}
		val snapshot = Pager(
			config = LargeLibraryPagingConfig,
			initialKey = null,
			pagingSourceFactory = { source },
		).flow.asSnapshot()

		assertTrue(snapshot.isNotEmpty(), "washed out: got " + snapshot.size + " rows")
		assertEquals(listOf(2L, 4L, 6L, 8L, 10L), snapshot.take(5), "first screen must keep the head of the filtered order")
		assertEquals(snapshot.sorted(), snapshot, "rows must stay strictly monotone across background pages")
		assertEquals(snapshot.size, snapshot.distinct().size, "no duplicate rows may appear at page boundaries")
	}

	@Test
	fun `filtered paging stays unique and in order after deep scrolling`() = runTest {
		val delegate = RecordingEntityPagingSource((1L..9_800L).toList())
		val source = BatchMappingPagingSource(delegate, diagnosticLabel = "repro-deep") { raw ->
			raw.filter { it % 2L == 0L }
		}
		val pager = Pager(
			config = LargeLibraryPagingConfig,
			initialKey = null,
			pagingSourceFactory = { source },
		)
		val snapshot = pager.flow.asSnapshot {
			scrollTo(4_000)
			appendScrollWhile { true }
		}
		// 9_800/2 = 4_900 visible rows; scrolling to the end must deliver them all
		// exactly once and in the original filtered order.
		assertEquals(4_900, snapshot.size)
		assertEquals(snapshot.sorted(), snapshot)
		assertEquals(snapshot.size, snapshot.distinct().size)
	}



	@Test
	fun `refresh after background invalidation keeps the visible rows instead of washing them out`() = runTest {
		// Integration through the real Pager: scroll deep into a filtered 9800-row
		// library, then invalidate (what entity_preferences/entity_binding writes
		// do at rest). The reload must keep the same visible favourites; before the
		// fix getRefreshKey() returned a mapped position that Room interpreted as a
		// raw OFFSET, so the viewport was replaced by entirely different rows.
		val raw = (1L..9_800L).toList()
		val pager = Pager(
			config = LargeLibraryPagingConfig,
			initialKey = null,
			pagingSourceFactory = {
				BatchMappingPagingSource(RecordingEntityPagingSource(raw), diagnosticLabel = "repro-refresh") { page ->
					page.filter { it % 2L == 0L }
				}
			},
		)
		val anchor = 3_000
		val before = pager.flow.asSnapshot { scrollTo(anchor) }
		// Output position 3_000 holds the 3_000th visible (even) row == raw 6_002.
		val expectedAtAnchor = 2L + 2L * anchor
		assertTrue(before.contains(expectedAtAnchor), "anchor row " + expectedAtAnchor + " must be loaded before refresh")

		val after = pager.flow.asSnapshot {
			scrollTo(anchor)
			refresh()
			scrollTo(anchor)
		}
		// The same visible row must still be present after the reload...
		assertTrue(after.contains(expectedAtAnchor), "refresh dropped the loaded row " + expectedAtAnchor)
		// ...and the reloaded window must START in the anchor's own neighbourhood
		// (raw ~6_000). Before the fix the refresh key was the raw anchor value,
		// so the window began at raw ~3_000 (output 1_500) and the user saw a
		// totally different half of the library wash in.
		assertTrue(
			after.take(20).all { it >= expectedAtAnchor - 512L },
			"refresh window started in the wrong region: " + after.take(5),
		)
		assertEquals(after.sorted(), after, "post-refresh rows must stay monotone")
		assertEquals(after.size, after.distinct().size, "post-refresh rows must stay unique")
	}

	@Test
	fun `unfiltered paging keeps its exact position after a background refresh`() = runTest {
		// Without filters output space == raw space; a reload must keep the same
		// row at the same place (this is the config most users run in).
		val raw = (1L..4_900L).toList()
		val pager = Pager(
			config = LargeLibraryPagingConfig,
			initialKey = null,
			pagingSourceFactory = {
				BatchMappingPagingSource(RecordingEntityPagingSource(raw), diagnosticLabel = "repro-identity") { page -> page }
			},
		)
		val expectedAtAnchor = 3_000L
		val before = pager.flow.asSnapshot { scrollTo(3_000) }
		assertTrue(before.contains(expectedAtAnchor), "row " + expectedAtAnchor + " must be loaded before refresh")
		val after = pager.flow.asSnapshot {
			scrollTo(3_000)
			refresh()
			scrollTo(3_000)
		}
		assertTrue(after.contains(expectedAtAnchor), "identity refresh must not drop the loaded row")
		// The refreshed window must start at or just before the anchor (a page
		// boundary at most), never half the library away.
		assertTrue(
			after.take(20).all { it in (expectedAtAnchor - 512L)..(expectedAtAnchor + 64L) },
			"identity refresh window started in the wrong region: " + after.take(5),
		)
		assertEquals(after.sorted(), after)
		assertEquals(after.size, after.distinct().size)
	}

	@Test
	fun `filtered prepend fills backward without overlapping the refresh page`() = runTest {
		val delegate = RecordingEntityPagingSource((1L..257L).toList())
		val source = BatchMappingPagingSource(delegate) { page ->
			page.filter { it % 2L == 0L }
		}
		val refresh = source.load(
			PagingSource.LoadParams.Refresh(84, LargeLibraryPagingConfig.initialLoadSize, false),
		) as PagingSource.LoadResult.Page
		val prepend = source.load(
			PagingSource.LoadParams.Prepend(
				key = requireNotNull(refresh.prevKey),
				loadSize = LargeLibraryPagingConfig.pageSize,
				placeholdersEnabled = false,
			),
		) as PagingSource.LoadResult.Page

		assertTrue(prepend.data.intersect(refresh.data.toSet()).isEmpty())
		assertTrue(prepend.data.last() < refresh.data.first())
		assertEquals((prepend.data + refresh.data).sorted(), prepend.data + refresh.data)
		assertEquals((prepend.data + refresh.data).size, (prepend.data + refresh.data).distinct().size)
	}

	private suspend fun assertPagedDataset(size: Int) {
		val delegate = RecordingEntityPagingSource((1L..size.toLong()).toList())
		val source = BatchMappingPagingSource(delegate) { page -> page.map { it * 10L } }
		val first = source.load(
			PagingSource.LoadParams.Refresh(
				key = null,
				loadSize = 64,
				placeholdersEnabled = false,
			),
		) as PagingSource.LoadResult.Page
		assertEquals(64, first.data.size)
		assertEquals(listOf(64), delegate.requestedLoadSizes)

		val second = source.load(
			PagingSource.LoadParams.Append(
				key = requireNotNull(first.nextKey),
				loadSize = 64,
				placeholdersEnabled = false,
			),
		) as PagingSource.LoadResult.Page
		val loaded = first.data + second.data
		assertEquals(128, loaded.size)
		assertEquals(loaded.size, loaded.distinct().size)
		assertEquals(listOf(64, 64), delegate.requestedLoadSizes)
		assertTrue(second.nextKey != null)
	}

	private class RecordingEntityPagingSource(
		private val entityIds: List<Long>,
	) : PagingSource<Int, Long>() {
		val requestedLoadSizes = mutableListOf<Int>()

		override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Long> {
			requestedLoadSizes += params.loadSize
			val key = params.key ?: 0
			val offset = when (params) {
				is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0)
				is LoadParams.Append -> key
				is LoadParams.Refresh -> key.coerceAtMost((entityIds.size - params.loadSize).coerceAtLeast(0))
			}
			val limit = if (params is LoadParams.Prepend) params.loadSize.coerceAtMost(key) else params.loadSize
			val end = (offset + limit).coerceAtMost(entityIds.size)
			return LoadResult.Page(
				data = entityIds.subList(offset, end),
				prevKey = offset.takeIf { it > 0 },
				nextKey = end.takeIf { it < entityIds.size },
				itemsBefore = offset,
				itemsAfter = entityIds.size - end,
			)
		}

		override fun getRefreshKey(state: PagingState<Int, Long>): Int? = state.anchorPosition
	}
}
