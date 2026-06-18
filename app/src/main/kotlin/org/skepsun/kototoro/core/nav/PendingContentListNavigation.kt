package org.skepsun.kototoro.core.nav

import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

object PendingContentListNavigation {

    private var pendingFilter: ContentListFilter? = null
    private var pendingSortOrder: SortOrder? = null

    fun set(
        filter: ContentListFilter?,
        sortOrder: SortOrder?,
    ) {
        pendingFilter = filter
        pendingSortOrder = sortOrder
    }

    fun consumeFilter(): ContentListFilter? = pendingFilter.also { pendingFilter = null }

    fun consumeSortOrder(): SortOrder? = pendingSortOrder.also { pendingSortOrder = null }

    fun clear() {
        pendingFilter = null
        pendingSortOrder = null
    }
}
