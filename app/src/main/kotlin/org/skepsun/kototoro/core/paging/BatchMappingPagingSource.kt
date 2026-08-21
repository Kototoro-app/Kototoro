package org.skepsun.kototoro.core.paging

import android.os.SystemClock
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LargeLibraryPagingConfig = androidx.paging.PagingConfig(
    pageSize = 64,
    initialLoadSize = 64,
    prefetchDistance = 24,
    enablePlaceholders = false,
)

/** Keeps two mapped favourite pages ahead without increasing cold-start work. */
val FavouriteLibraryPagingConfig = androidx.paging.PagingConfig(
    pageSize = 64,
    initialLoadSize = 64,
    prefetchDistance = 128,
    enablePlaceholders = false,
)

/** Maps a Room positional page as one batch so downstream joins stay O(pages), not O(items). */
class BatchMappingPagingSource<Input : Any, Output : Any>(
    private val delegate: PagingSource<Int, Input>,
    private val diagnosticLabel: String? = null,
    private val transform: suspend (List<Input>) -> List<Output>,
) : PagingSource<Int, Output>() {

    init {
        delegate.registerInvalidatedCallback(::invalidate)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Output> {
        val loadStartedAt = SystemClock.elapsedRealtime()
        val initial = delegate.load(params)
        if (initial !is LoadResult.Page) {
            return when (initial) {
                is LoadResult.Error -> LoadResult.Error(initial.throwable)
                is LoadResult.Invalid -> LoadResult.Invalid()
                is LoadResult.Page -> error("unreachable")
            }
        }
        val firstPage = initial
        var page: LoadResult.Page<Int, Input> = firstPage
        val mapped = ArrayList<Output>(params.loadSize)
        var rawItemCount = 0
        var rawPageCount = 0
        while (true) {
            rawItemCount += page.data.size
            rawPageCount++
            mapped += withContext(Dispatchers.Default) {
                transform(page.data)
            }
            val nextKey = page.nextKey
            if (mapped.size >= params.loadSize || nextKey == null) {
                diagnosticLabel?.let { label ->
                    Log.d(
                        "LibraryPaging",
                        "$label ${params.javaClass.simpleName} rawPages=$rawPageCount rawItems=$rawItemCount " +
                            "mappedItems=${mapped.size} elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAt}",
                    )
                }
                return LoadResult.Page(
                    data = mapped,
                    prevKey = firstPage.prevKey,
                    nextKey = nextKey,
                    itemsBefore = firstPage.itemsBefore,
                    itemsAfter = page.itemsAfter,
                )
            }
            page = when (val next = delegate.load(
                LoadParams.Append(
                    key = nextKey,
                    loadSize = params.loadSize,
                    placeholdersEnabled = params.placeholdersEnabled,
                ),
            )) {
                is LoadResult.Page -> next
                is LoadResult.Error -> return LoadResult.Error(next.throwable)
                is LoadResult.Invalid -> return LoadResult.Invalid()
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Output>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        // The delegate is a Room LIMIT/OFFSET positional source over RAW rows,
        // while `transform` may filter or expand rows (quick filters, NSFW /
        // blacklist, group tabs, separators). A bare anchorPosition is a position
        // in the MAPPED space and does not match the RAW offset the delegate
        // expects: passing it straight through made an invalidation-driven reload
        // start at a raw offset unrelated to the rows the user was looking at, so
        // the already-loaded favourites got replaced by other ones.
        //
        // PagingState.closestPageToPosition() locates the page holding the anchor
        // in the mapped space (it accumulates page data sizes). That page's
        // prevKey/nextKey are RAW offsets into the delegate which the Room source
        // can reload directly, so the refreshed window stays on the same rows.
        val anchorPage = state.closestPageToPosition(anchorPosition)
        // prevKey is the raw offset of the page before the one holding the
        // anchor; every page except the first one carries a raw prevKey, and an
        // anchor inside the first page correctly gets null (reload from the top).
        return anchorPage?.prevKey
    }
}
