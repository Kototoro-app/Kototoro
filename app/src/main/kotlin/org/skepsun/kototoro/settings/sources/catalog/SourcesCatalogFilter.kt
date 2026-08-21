package org.skepsun.kototoro.settings.sources.catalog

import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Source type filter for catalog
 */
enum class SourceTypeFilter {
    ALL,      // Show all sources
    NATIVE,   // Show only native sources
    JSON,     // Show only JSON sources
}

data class SourcesCatalogFilter(
    val types: Set<ContentType>,
    val locale: String?,
    val isNewOnly: Boolean,
    val sourceType: SourceTypeFilter = SourceTypeFilter.ALL,
)
