package org.skepsun.kototoro.search.ui.compose

import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind

data class SearchNavigationRequest(
    val query: String,
    val kind: SearchKind,
    val sourceTypes: Set<SourceType>,
    val contentKinds: Set<SearchContentKind>,
    val advancedQuery: AdvancedSearchParams?,
    val pinnedOnly: Boolean,
    val hideEmpty: Boolean,
    val requestId: Long,
)
