package org.skepsun.kototoro.main.ui

import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.sourceTypesFromTags
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest
import org.skepsun.kototoro.search.ui.suggestion.SearchSuggestionViewModel

/**
 * Owns the main shell's top-bar / filter-bar / inset UI state that previously lived
 * directly on [MainActivity]: the search query + pending navigation, the quick-filter
 * selection state, chrome metrics, and all the logic that derives them from the
 * active [SearchBarFilterCallback]. [MainActivity] keeps a thin delegating surface for
 * external callers and still performs navigation/persistence.
 */
@Stable
class MainChromeController(
    private val settings: AppSettings,
    private val searchSuggestionViewModel: SearchSuggestionViewModel,
    private val decorViewProvider: () -> View,
) {
    var topBarHeightPx = 0
    var bottomNavHeightPx = 0
    var containerTopInsetPx = 0
    var containerBottomInsetPx = 0

    var searchQuery by mutableStateOf("")
        private set
    var searchNavigationRequest by mutableStateOf<SearchNavigationRequest?>(null)
        private set
    private var nextSearchRequestId = 0L

    private val activeFilterCallbacks = LinkedHashSet<SearchBarFilterCallback>()
    var currentFilterCallback: SearchBarFilterCallback? = null
        private set

    // Highlights the states MainActivity feeds into the shell (kept as plain
    // properties; the shell reads them as Compose state through these vars).
    var activeFilterContentType by mutableStateOf<ContentType?>(null)
        private set
    var activeFilterSourceTags by mutableStateOf<Set<SourceTag>>(emptySet())
        private set
    var isLanguagePresetFilterVisible by mutableStateOf(false)
        private set
    var isContentTypeFilterVisible by mutableStateOf(true)
        private set
    var isSourceTagFilterVisible by mutableStateOf(true)
        private set
    var availableSourceTags by mutableStateOf(SourceTag.quickFilterEntries)
        private set
    var enabledSourceTags by mutableStateOf(SourceTag.quickFilterEntries.toSet())
        private set
    var enabledContentTypes by mutableStateOf(allTopBarContentTypes())
        private set

    fun setActiveFilterCallback(callback: SearchBarFilterCallback) {
        activeFilterCallbacks.remove(callback)
        activeFilterCallbacks.add(callback)
        currentFilterCallback = callback
        refreshFilters()
    }

    fun clearActiveFilterCallback(callback: SearchBarFilterCallback) {
        activeFilterCallbacks.remove(callback)
        currentFilterCallback = activeFilterCallbacks.lastOrNull()
        if (currentFilterCallback != null) {
            refreshFilters()
        } else {
            clearActiveFilters()
        }
    }

    fun refreshFilters() {
        val callback = currentFilterCallback ?: return
        val sourceTagEntries = callback.getSourceTagEntries()
        availableSourceTags = sourceTagEntries
        isLanguagePresetFilterVisible = callback.isLanguagePresetFilterVisible() && settings.isShowLanguagePresetFilter
        isContentTypeFilterVisible = callback.isContentTypeFilterVisible() && settings.isShowContentTypeFilter
        isSourceTagFilterVisible = callback.isSourceTagFilterVisible() &&
            settings.isShowSourceTagFilter &&
            sourceTagEntries.isNotEmpty()
        applyConfiguredLanguagePreset()

        val selectedTab = if (isContentTypeFilterVisible) {
            callback.getSelectedContentType()
        } else {
            settings.hiddenContentType.toBrowseGroupTab()
        }
        if (!isContentTypeFilterVisible) {
            callback.applyContentTypeSelection(selectedTab)
        }
        activeFilterContentType = selectedTab.toContentTypeOrNull()

        val selectedSourceTags = if (isSourceTagFilterVisible) {
            callback.getSelectedSourceTags()
        } else {
            settings.hiddenSourceTag.toSourceTagSelection()
        }
        if (!isSourceTagFilterVisible) {
            callback.applySourceTagSelection(selectedSourceTags)
        }
        activeFilterSourceTags = selectedSourceTags

        enabledSourceTags = sourceTagEntries.filterTo(linkedSetOf()) { tag ->
            callback.isSourceTagEnabled(tag)
        }
        enabledContentTypes = buildSet {
            if (callback.isContentTypeEnabled(BrowseGroupTab.Content)) {
                add(ContentType.MANGA)
            }
            if (callback.isContentTypeEnabled(BrowseGroupTab.Novel)) {
                add(ContentType.NOVEL)
            }
            if (callback.isContentTypeEnabled(BrowseGroupTab.Video)) {
                add(ContentType.VIDEO)
            }
        }
        syncSearchSuggestionFilters()
    }

    fun onSourceTagFilterClick(anchorView: View?): Boolean {
        val anchor = anchorView ?: decorViewProvider()
        return currentFilterCallback?.onFilterIconClicked(anchor) == true
    }

    fun updateSearchQuery(query: String) {
        if (searchQuery != query) {
            searchQuery = query
        }
        searchSuggestionViewModel.onQueryChanged(query)
    }

    fun clearSearchQuery() {
        updateSearchQuery("")
    }

    fun consumeSearchNavigation() {
        clearSearchQuery()
        searchNavigationRequest = null
    }

    fun restoreSearchQuery(value: String) {
        searchQuery = value
    }

    fun requestSearchNavigation(request: SearchNavigationRequest) {
        nextSearchRequestId += 1
        searchNavigationRequest = request.copy(requestId = nextSearchRequestId)
    }

    fun syncSearchSuggestionFilters() {
        searchSuggestionViewModel.setSourceTypes(sourceTypesFromTags(activeFilterSourceTags))
        searchSuggestionViewModel.setContentKinds(activeFilterContentType.toSearchContentKinds())
    }

    fun clearActiveFilters() {
        activeFilterCallbacks.clear()
        currentFilterCallback = null
        activeFilterContentType = if (settings.isShowContentTypeFilter) {
            null
        } else {
            settings.hiddenContentType.toBrowseGroupTab().toContentTypeOrNull()
        }
        activeFilterSourceTags = if (settings.isShowSourceTagFilter) {
            emptySet()
        } else {
            settings.hiddenSourceTag.toSourceTagSelection()
        }
        isLanguagePresetFilterVisible = settings.isShowLanguagePresetFilter
        isContentTypeFilterVisible = settings.isShowContentTypeFilter
        isSourceTagFilterVisible = settings.isShowSourceTagFilter
        availableSourceTags = SourceTag.quickFilterEntries
        enabledSourceTags = SourceTag.quickFilterEntries.toSet()
        enabledContentTypes = allTopBarContentTypes()
        applyConfiguredLanguagePreset()
        syncSearchSuggestionFilters()
    }

    fun applyConfiguredLanguagePreset() {
        if (!settings.isShowLanguagePresetFilter) {
            val presetId = settings.hiddenLanguagePreset.toPresetId()
            if (settings.activeSourcePresetId != presetId) {
                settings.activeSourcePresetId = presetId
            }
        }
    }
}

/**
 * CompositionLocal exposing the main shell's [MainChromeController] to nested routes,
 * replacing the previous silent `LocalContext.current as? MainActivity` lookups for
 * filter-bar callback registration. Provided by [org.skepsun.kototoro.main.ui.MainActivity]
 * at the shell root; null outside the main shell (e.g. standalone activities).
 */
val LocalMainChromeController = staticCompositionLocalOf<MainChromeController?> { null }

private fun ContentType?.toSearchContentKinds(): Set<SearchContentKind> = when (this) {
    ContentType.MANGA -> setOf(SearchContentKind.MANGA)
    ContentType.NOVEL, ContentType.HENTAI_NOVEL -> setOf(SearchContentKind.NOVEL)
    ContentType.VIDEO, ContentType.HENTAI_VIDEO -> setOf(SearchContentKind.VIDEO)
    else -> ALL_SEARCH_CONTENT_KINDS
}

private fun BrowseGroupTab.toContentTypeOrNull(): ContentType? = when (this) {
    BrowseGroupTab.Content -> ContentType.MANGA
    BrowseGroupTab.Novel -> ContentType.NOVEL
    BrowseGroupTab.Video -> ContentType.VIDEO
    BrowseGroupTab.All -> null
}

private fun String?.toBrowseGroupTab(): BrowseGroupTab = BrowseGroupTab.fromId(this ?: BrowseGroupTab.All.id)

private fun String?.toSourceTagSelection(): Set<SourceTag> {
    if (this.isNullOrBlank() || this == "all") {
        return emptySet()
    }
    return SourceTag.sanitizeQuickFilterSelection(
        split(',').asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "all" }
            .mapNotNull { raw ->
                runCatching { SourceTag.valueOf(raw) }.getOrNull()
                    ?: SourceTag.entries.firstOrNull { it.id == raw }
            }
            .toSet(),
    )
}

private fun String?.toPresetId(): Long = this?.toLongOrNull()?.takeIf { it > 0L } ?: -1L

private fun allTopBarContentTypes(): Set<ContentType> = setOf(
    ContentType.MANGA,
    ContentType.NOVEL,
    ContentType.VIDEO,
)