package org.skepsun.kototoro.explore.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * Persisted configuration for a single row in the Explore Source Discovery Feed.
 *
 * A row targets a content source by [sourceName] and optionally references a saved filter
 * ([savedFilterId], matching [org.skepsun.kototoro.filter.data.PersistableFilter.id]) or a
 * keyword [query]. When both [savedFilterId] and [query] are null/blank, the row loads the
 * source's default listing.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class DiscoveryFeedRow(
    @SerialName("id")
    val id: String,
    @SerialName("source_name")
    val sourceName: String,
    @SerialName("saved_filter_id")
    val savedFilterId: Int? = null,
    @SerialName("query")
    val query: String? = null,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("sort_order")
    val sortOrder: Int = 0,
    @SerialName("is_hidden")
    val isHidden: Boolean = false,
) {

    val normalizedQuery: String?
        get() = query?.trim()?.takeIf { it.isNotEmpty() }
}
