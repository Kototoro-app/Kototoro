package org.skepsun.kototoro.search.domain

import org.skepsun.kototoro.core.jsonsource.SourceType

/**
 * The scope of a content search: which source types and content kinds are searched, whether only pinned or only
 * non-empty sources take part, and which language preset narrows the sources.
 *
 * [languagePresetId] is a search-scoped value, deliberately kept apart from the global browsing preset
 * (`AppSettings.activeSourcePresetId`):
 * - [PRESET_FOLLOW_GLOBAL] follows whatever the user picked for browsing, without ever writing it back;
 * - [PRESET_ALL] means the user explicitly chose "All" for search;
 * - a positive id is a preset the user explicitly pinned for search.
 */
data class SearchFilters(
    val sourceTypes: Set<SourceType> = ALL_SOURCE_TYPES,
    val contentKinds: Set<SearchContentKind> = ALL_SEARCH_CONTENT_KINDS,
    val pinnedOnly: Boolean = false,
    val hideEmpty: Boolean = false,
    val languagePresetId: Long = PRESET_FOLLOW_GLOBAL,
) {

    fun normalized(): SearchFilters = copy(
        sourceTypes = sourceTypes.ifEmpty { ALL_SOURCE_TYPES },
        contentKinds = contentKinds.ifEmpty { ALL_SEARCH_CONTENT_KINDS },
        languagePresetId = when {
            languagePresetId > 0L -> languagePresetId
            languagePresetId == PRESET_ALL -> PRESET_ALL
            else -> PRESET_FOLLOW_GLOBAL
        },
    )

    /** The preset id that actually filters sources, given the global browsing preset. */
    fun resolveLanguagePresetId(globalPresetId: Long): Long = when {
        languagePresetId == PRESET_FOLLOW_GLOBAL -> globalPresetId
        else -> languagePresetId
    }

    fun hasSameSearchScope(other: SearchFilters): Boolean = copy(hideEmpty = false) == other.copy(hideEmpty = false)

    companion object {
        /** Search follows the global browsing preset (default, and the value Reset restores). */
        const val PRESET_FOLLOW_GLOBAL: Long = -2L

        /** The user explicitly chose "All" for search, so the global preset must not narrow it. */
        const val PRESET_ALL: Long = -1L
    }
}
