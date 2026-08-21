package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListQuickFilter
import javax.inject.Inject

class SuggestionsListQuickFilter @Inject constructor(
    private val settings: AppSettings,
    private val suggestionRepository: SuggestionRepository,
) : ContentListQuickFilter(settings) {

    override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList(6) {
        suggestionRepository.getTopTags(5).mapTo(this) {
            ListFilterOption.Tag(it)
        }
        if (!settings.isSuggestionsExcludeNsfw) {
            add(ListFilterOption.Macro.NSFW)
            add(ListFilterOption.SFW)
        }
        suggestionRepository.getTopSources(3).mapTo(this) {
            ListFilterOption.Source(it)
        }
    }
}
