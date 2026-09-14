package org.skepsun.kototoro.list.ui.compose

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.domain.ListSortOrder

/** Presentation-only grouping; stored sort orders and their comparator semantics stay intact. */
internal data class ListSortOption(
    @param:StringRes val titleResId: Int,
    val ascending: ListSortOrder? = null,
    val descending: ListSortOrder? = null,
) {
    fun contains(order: ListSortOrder?) = order != null && (order == ascending || order == descending)

    fun orderFor(isDescending: Boolean): ListSortOrder = checkNotNull(
        if (isDescending) descending ?: ascending else ascending ?: descending,
    )
}

internal fun listSortOptions(orders: List<ListSortOrder>): List<ListSortOption> = orders.map { order ->
    when (order) {
        ListSortOrder.NEWEST, ListSortOrder.OLDEST -> ListSortOption(
            R.string.sort_by_added_date, ListSortOrder.OLDEST, ListSortOrder.NEWEST,
        )
        ListSortOrder.ALPHABETIC, ListSortOrder.ALPHABETIC_REVERSE -> ListSortOption(
            R.string.sort_by_title, ListSortOrder.ALPHABETIC, ListSortOrder.ALPHABETIC_REVERSE,
        )
        ListSortOrder.PROGRESS, ListSortOrder.UNREAD -> ListSortOption(
            R.string.progress, ListSortOrder.UNREAD, ListSortOrder.PROGRESS,
        )
        ListSortOrder.LAST_READ, ListSortOrder.LONG_AGO_READ -> ListSortOption(
            R.string.sort_by_last_read, ListSortOrder.LONG_AGO_READ, ListSortOrder.LAST_READ,
        )
        ListSortOrder.RATING -> ListSortOption(R.string.sort_by_rating, descending = order)
        else -> ListSortOption(order.titleResId, descending = order)
    }.let { option ->
        option.copy(
            ascending = option.ascending?.takeIf { it in orders },
            descending = option.descending?.takeIf { it in orders },
        )
    }
}.distinct()
