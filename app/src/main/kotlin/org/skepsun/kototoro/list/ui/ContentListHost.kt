package org.skepsun.kototoro.list.ui

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.util.ext.EventFlow
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content

/**
 * Everything [org.skepsun.kototoro.list.ui.compose.AppContentListRoute] drives, without
 * requiring a [androidx.lifecycle.ViewModel].
 *
 * [ContentListViewModel] implements it for the paging pages; pages whose state lives in
 * a shared screen-level holder (the favourites library, whose snapshot belongs to
 * `FavouritesContainerViewModel`) supply a thin per-page adapter instead of another
 * child ViewModel. The interface deliberately carries only what the route touches:
 * streams it renders, chrome callbacks it forwards, and the entity-id resolution the
 * shared transition / details routing needs.
 */
interface ContentListHost {

    /** Static content of the list; `pagingContent` is null for these pages. */
    val content: StateFlow<List<ListModel>>

    /** Paged content of the list, `null` when the page renders [content] directly. */
    val pagingContent: Flow<PagingData<ListModel>>?

    val hasMoreItems: StateFlow<Boolean>

    val isLoading: StateFlow<Boolean>

    val listMode: StateFlow<ListMode>

    val gridScale: StateFlow<Float>

    val onError: EventFlow<Throwable>

    val onContentMessage: EventFlow<String>

    val onContentActionHostRequest: EventFlow<ContentActionHostRequest>

    val currentSourceTags: StateFlow<Set<SourceTag>>

    val currentGroupTab: StateFlow<BrowseGroupTab>

    /** Pull-to-refresh of the page itself (a shared snapshot source may ignore it). */
    fun onRefresh()

    fun onRetry()

    /** `true` when the click was handled and the route must not navigate. */
    fun onContentClick(content: Content): Boolean = false

    fun setSelectedSourceTags(tags: Set<SourceTag>) = Unit

    fun setSelectedGroupTab(tab: BrowseGroupTab) = Unit

    /**
     * Entity id behind a list-item id. Pages whose items are entity rows answer with the
     * id itself so details navigation never resolves a manga that merely shares the id.
     */
    fun resolveEntityIdForUiItemId(id: Long): Long? = null

    /** Display projection preferred by details routing for an item id, if any. */
    fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? = null
}
