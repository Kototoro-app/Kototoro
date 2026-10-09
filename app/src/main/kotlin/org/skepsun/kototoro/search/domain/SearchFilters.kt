package org.skepsun.kototoro.search.domain

import org.skepsun.kototoro.core.jsonsource.SourceType

data class SearchFilters(
    val sourceTypes: Set<SourceType> = ALL_SOURCE_TYPES,
    val contentKinds: Set<SearchContentKind> = ALL_SEARCH_CONTENT_KINDS,
    val pinnedOnly: Boolean = false,
    val hideEmpty: Boolean = false,
    val languagePresetId: Long = -1L,
) {
    fun normalized(): SearchFilters = copy(
        sourceTypes = sourceTypes.ifEmpty { ALL_SOURCE_TYPES },
        contentKinds = contentKinds.ifEmpty { ALL_SEARCH_CONTENT_KINDS },
        languagePresetId = languagePresetId.takeIf { it > 0L } ?: -1L,
    )

    fun hasSameSearchScope(other: SearchFilters): Boolean = copy(hideEmpty = false) == other.copy(hideEmpty = false)
}
