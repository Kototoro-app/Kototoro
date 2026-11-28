package org.skepsun.kototoro.search.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * The persisted search scope: the second of the three tiers of filter state.
 *
 * Tier 1 is the global browsing preset ([AppSettings.activeSourcePresetId]) that Explore, Home, the top bar and Space
 * switching own. Tier 2 is this search scope, which the search page and the global search overlay own. Tier 3 is the
 * detail page's reading-source scope, which is intentionally not persisted at all.
 *
 * Keeping the search scope here means applying filters in search never mutates the browsing preset, and changing the
 * source of a work on the detail page never changes what the search page searches.
 */
class SearchScopePrefs(private val prefs: SharedPreferences) {

    /** Whether the user has ever applied a search scope, so defaults can come from elsewhere until they do. */
    val isStored: Boolean
        get() = prefs.contains(AppSettings.KEY_SEARCH_SCOPE_SOURCE_TYPES) ||
            prefs.contains(AppSettings.KEY_SEARCH_SCOPE_CONTENT_KINDS) ||
            prefs.contains(AppSettings.KEY_SEARCH_SCOPE_LANGUAGE_PRESET_ID)

    fun load(): SearchFilters {
        val sourceTypes = prefs.getStringSet(AppSettings.KEY_SEARCH_SCOPE_SOURCE_TYPES, null)
            ?.let { names -> sourceTypesFromNames(names) }
            ?: ALL_SOURCE_TYPES
        val contentKinds = prefs.getStringSet(AppSettings.KEY_SEARCH_SCOPE_CONTENT_KINDS, null)
            ?.let { names -> searchContentKindsFromNames(names) }
            ?: ALL_SEARCH_CONTENT_KINDS
        val pinnedOnly = prefs.getBoolean(AppSettings.KEY_SEARCH_SCOPE_PINNED_ONLY, false)
        val hideEmpty = readHideEmpty()
        val languagePresetId = readLanguagePresetId()
        return SearchFilters(
            sourceTypes = sourceTypes,
            contentKinds = contentKinds,
            pinnedOnly = pinnedOnly,
            hideEmpty = hideEmpty,
            languagePresetId = languagePresetId,
        ).normalized()
    }

    /** The stored scope, or null when the user has never applied one (so callers can seed their own default). */
    fun loadOrNull(): SearchFilters? = if (isStored) load() else null

    fun save(filters: SearchFilters) {
        val normalized = filters.normalized()
        prefs.edit {
            putStringSet(
                AppSettings.KEY_SEARCH_SCOPE_SOURCE_TYPES,
                normalized.sourceTypes.mapTo(mutableSetOf()) { type -> type.name },
            )
            putStringSet(
                AppSettings.KEY_SEARCH_SCOPE_CONTENT_KINDS,
                normalized.contentKinds.mapTo(mutableSetOf()) { kind -> kind.name },
            )
            putBoolean(AppSettings.KEY_SEARCH_SCOPE_PINNED_ONLY, normalized.pinnedOnly)
            putBoolean(AppSettings.KEY_SEARCH_SCOPE_HIDE_EMPTY, normalized.hideEmpty)
            putLong(AppSettings.KEY_SEARCH_SCOPE_LANGUAGE_PRESET_ID, normalized.languagePresetId)
        }
    }

    /** Restores the defaults: every type, every kind, no pin/hide-empty restriction, following the global preset. */
    fun reset() = save(SearchFilters())

    private fun readHideEmpty(): Boolean = if (prefs.contains(AppSettings.KEY_SEARCH_SCOPE_HIDE_EMPTY)) {
        prefs.getBoolean(AppSettings.KEY_SEARCH_SCOPE_HIDE_EMPTY, false)
    } else {
        // Migration: the single "hide empty sources" search switch used to be remembered globally.
        prefs.getBoolean(AppSettings.KEY_SEARCH_HIDE_EMPTY_SOURCES, false)
    }

    private fun readLanguagePresetId(): Long = try {
        prefs.getLong(AppSettings.KEY_SEARCH_SCOPE_LANGUAGE_PRESET_ID, SearchFilters.PRESET_FOLLOW_GLOBAL)
    } catch (_: ClassCastException) {
        SearchFilters.PRESET_FOLLOW_GLOBAL
    }
}
