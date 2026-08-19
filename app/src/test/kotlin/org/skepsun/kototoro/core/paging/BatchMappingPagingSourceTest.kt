package org.skepsun.kototoro.core.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
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
			val offset = params.key ?: 0
			val end = (offset + params.loadSize).coerceAtMost(entityIds.size)
			return LoadResult.Page(
				data = entityIds.subList(offset, end),
				prevKey = (offset - params.loadSize).takeIf { it >= 0 },
				nextKey = end.takeIf { it < entityIds.size },
				itemsBefore = offset,
				itemsAfter = entityIds.size - end,
			)
		}

		override fun getRefreshKey(state: PagingState<Int, Long>): Int? = state.anchorPosition
	}
}
