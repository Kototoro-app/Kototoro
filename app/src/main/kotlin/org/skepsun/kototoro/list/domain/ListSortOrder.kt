package org.skepsun.kototoro.list.domain

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

@get:StringRes
val ListSortOrder.titleResId: Int
    get() = when (this) {
        ListSortOrder.NEWEST -> R.string.order_added
        ListSortOrder.OLDEST -> R.string.order_oldest
        ListSortOrder.PROGRESS -> R.string.progress
        ListSortOrder.UNREAD -> R.string.unread
        ListSortOrder.ALPHABETIC -> R.string.by_name
        ListSortOrder.ALPHABETIC_REVERSE -> R.string.by_name_reverse
        ListSortOrder.RATING -> R.string.by_rating
        ListSortOrder.RELEVANCE -> R.string.by_relevance
        ListSortOrder.NEW_CHAPTERS -> R.string.new_chapters
        ListSortOrder.LAST_READ -> R.string.last_read
        ListSortOrder.LONG_AGO_READ -> R.string.long_ago_read
        ListSortOrder.UPDATED -> R.string.updated
        ListSortOrder.MANUAL -> R.string.order_manual
    }
