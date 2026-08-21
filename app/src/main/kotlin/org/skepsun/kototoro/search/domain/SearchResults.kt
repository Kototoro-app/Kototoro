package org.skepsun.kototoro.search.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder

data class SearchResults(
    val listFilter: ContentListFilter,
    val sortOrder: SortOrder,
    val manga: List<Content>,
)
