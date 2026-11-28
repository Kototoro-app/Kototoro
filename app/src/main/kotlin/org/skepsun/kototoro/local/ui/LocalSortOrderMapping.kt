package org.skepsun.kototoro.local.ui

import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * 本地库提供的排序项，顺序即共享排序控件里的呈现顺序：
 * 添加时间（新→旧 / 旧→新）、标题（A→Z / Z→A）、评分（高→低）。
 */
internal val LocalLibrarySortOrders: List<ListSortOrder> = listOf(
    ListSortOrder.NEWEST,
    ListSortOrder.OLDEST,
    ListSortOrder.ALPHABETIC,
    ListSortOrder.ALPHABETIC_REVERSE,
    ListSortOrder.RATING,
)

/**
 * 界面侧的 [ListSortOrder] 与仓库侧的 [SortOrder] 是两套枚举，这里做一对一换算。
 * [SortOrder] 没有 OLDEST，最早的排序用 NEWEST_ASC 表达。
 */
internal fun ListSortOrder.toLocalRepositorySortOrder(): SortOrder = when (this) {
    ListSortOrder.OLDEST -> SortOrder.NEWEST_ASC
    ListSortOrder.ALPHABETIC -> SortOrder.ALPHABETICAL
    ListSortOrder.ALPHABETIC_REVERSE -> SortOrder.ALPHABETICAL_DESC
    ListSortOrder.RATING -> SortOrder.RATING
    else -> SortOrder.NEWEST
}

internal fun SortOrder.toLocalListSortOrder(): ListSortOrder = when (this) {
    SortOrder.NEWEST_ASC,
    SortOrder.UPDATED_ASC,
    SortOrder.ADDED_ASC -> ListSortOrder.OLDEST

    SortOrder.ALPHABETICAL -> ListSortOrder.ALPHABETIC
    SortOrder.ALPHABETICAL_DESC -> ListSortOrder.ALPHABETIC_REVERSE
    SortOrder.RATING,
    SortOrder.RATING_ASC -> ListSortOrder.RATING

    else -> ListSortOrder.NEWEST
}
