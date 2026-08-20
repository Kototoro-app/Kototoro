package org.skepsun.kototoro.core.nav

import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

object PendingContentListNavigation {

    private var pendingFilter: ContentListFilter? = null
    private var pendingSortOrder: SortOrder? = null
    private var pendingSourceName: String? = null

    fun set(
        filter: ContentListFilter?,
        sortOrder: SortOrder?,
    ) {
        pendingFilter = filter
        pendingSortOrder = sortOrder
    }

    /** Navigation 3 does not map route arguments into the entry's SavedStateHandle,
     *  so the source name is handed over explicitly before the list ViewModel is created. */
    fun setSource(sourceName: String?) {
        pendingSourceName = sourceName
    }

    fun peekSourceName(): String? = pendingSourceName

    fun consumeSourceName(): String? = pendingSourceName.also { pendingSourceName = null }

    fun consumeFilter(): ContentListFilter? = pendingFilter.also { pendingFilter = null }

    fun consumeSortOrder(): SortOrder? = pendingSortOrder.also { pendingSortOrder = null }

    fun clear() {
        pendingFilter = null
        pendingSortOrder = null
        pendingSourceName = null
    }
}
