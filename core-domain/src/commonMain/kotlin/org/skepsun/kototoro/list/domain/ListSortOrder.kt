package org.skepsun.kototoro.list.domain

enum class ListSortOrder {

    NEWEST,
    OLDEST,
    PROGRESS,
    UNREAD,
    ALPHABETIC,
    ALPHABETIC_REVERSE,
    RATING,
    RELEVANCE,
    NEW_CHAPTERS,
    LAST_READ,
    LONG_AGO_READ,
    UPDATED,
    MANUAL,
    UNREAD_CHAPTERS,
    UNREAD_CHAPTERS_ASC,
    ;

    fun isGroupingSupported() = this == LAST_READ || this == NEWEST || this == PROGRESS

    internal fun <T> compareUnreadChapters(selector: (T) -> Int?): Comparator<T> =
        compareBy(nullsLast(if (this == UNREAD_CHAPTERS_ASC) naturalOrder<Int>() else reverseOrder<Int>()), selector)

    companion object {

        val HISTORY: Set<ListSortOrder> = setOf(
            LAST_READ,
            LONG_AGO_READ,
            NEWEST,
            OLDEST,
            PROGRESS,
            UNREAD,
            ALPHABETIC,
            ALPHABETIC_REVERSE,
            NEW_CHAPTERS,
            UPDATED,
            UNREAD_CHAPTERS,
            UNREAD_CHAPTERS_ASC,
        ).sortedBy { it.ordinal }.toSet()
        val FAVORITES: Set<ListSortOrder> = setOf(
            ALPHABETIC,
            ALPHABETIC_REVERSE,
            NEWEST,
            OLDEST,
            RATING,
            NEW_CHAPTERS,
            PROGRESS,
            UNREAD,
            LAST_READ,
            LONG_AGO_READ,
            UPDATED,
            MANUAL,
            UNREAD_CHAPTERS,
            UNREAD_CHAPTERS_ASC,
        ).sortedBy { it.ordinal }.toSet()
        val SUGGESTIONS: Set<ListSortOrder> = setOf(RELEVANCE)

        fun favourites(categoryId: Long): List<ListSortOrder> =
            if (categoryId == -1L) {
                FAVORITES.filter { it != MANUAL }.sortedBy { it.ordinal }
            } else {
                FAVORITES.sortedBy { it.ordinal }
            }

        operator fun invoke(value: String, fallback: ListSortOrder) = entries.find { it.name == value } ?: fallback
    }
}
