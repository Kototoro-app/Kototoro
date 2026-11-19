package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class SuggestionsListQuickFilter @Inject constructor(
    private val settings: AppSettings,
    private val suggestionRepository: SuggestionRepository,
) : ContentListQuickFilter(settings) {

    private var scopeKey: Any? = null
    private var inScope: (ContentSource) -> Boolean = { true }

    /**
     * Limits the tag and source chips to what the list itself shows (the active space or group tab), so a manga space
     * does not offer the tags of videos. Chips are rebuilt only when [key] changes.
     */
    @Synchronized
    fun useScope(key: Any, predicate: (ContentSource) -> Boolean) {
        if (key == scopeKey) return
        scopeKey = key
        inScope = predicate
        invalidateAvailableOptions()
    }

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList(6) {
        val accepts = synchronized(this@SuggestionsListQuickFilter) { inScope }
        suggestionRepository.getTopTags(5, accepts).mapTo(this) {
            ListFilterOption.Tag(it)
        }
        if (!settings.isSuggestionsExcludeNsfw) {
            add(ListFilterOption.Macro.NSFW)
            add(ListFilterOption.SFW)
        }
        suggestionRepository.getTopSources(3, accepts).mapTo(this) {
            ListFilterOption.Source(it)
        }
    }
}
