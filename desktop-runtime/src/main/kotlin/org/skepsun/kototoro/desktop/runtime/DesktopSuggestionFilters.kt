package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.isNsfw

/**
 * One chip of Android's suggestions quick filter (`SuggestionsListQuickFilter`): the five most frequent tags, the two
 * adult macros while adult works are not excluded, and the three most frequent sources.
 *
 * Android filters inside SQL against `manga_tags` and `manga.source`; the Windows suggestions page already holds the
 * stored works, so it counts and matches them here instead. Tags therefore match by title (Android matches the tag
 * row), which is what the chips show anyway.
 */
sealed interface DesktopSuggestionFilter {

    /** Android's `ListFilterOption.Tag`: a tag title shared by several suggested works. */
    data class Tag(val title: String) : DesktopSuggestionFilter

    /** Android's `ListFilterOption.Source`: one source that contributed several suggested works. */
    data class Source(val name: String) : DesktopSuggestionFilter

    /** Android's `ListFilterOption.Macro.NSFW` chip. */
    data object Adult : DesktopSuggestionFilter

    /** Android's `ListFilterOption.SFW` chip. */
    data object Safe : DesktopSuggestionFilter
}

/**
 * The chips the page offers, in Android's order: tags, then the two adult macros, then sources. Nothing is offered for
 * an empty list, and [includeAdultChips] is Android's `!isSuggestionsExcludeNsfw` — with adult works excluded from
 * recommendations there is nothing for those two chips to split.
 */
fun desktopSuggestionFilters(
    items: List<SourceContent>,
    includeAdultChips: Boolean = true,
    tagLimit: Int = 5,
    sourceLimit: Int = 3,
): List<DesktopSuggestionFilter> = buildList {
    if (items.isEmpty()) return@buildList
    topTagTitles(items, tagLimit).mapTo(this) { DesktopSuggestionFilter.Tag(it) }
    if (includeAdultChips) {
        add(DesktopSuggestionFilter.Adult)
        add(DesktopSuggestionFilter.Safe)
    }
    topSourceNames(items, sourceLimit).mapTo(this) { DesktopSuggestionFilter.Source(it) }
}

/**
 * Android's quick filter semantics: every chosen chip has to match (the DAO ANDs its conditions) and no chip means no
 * narrowing. Both adult chips together therefore select nothing — the same empty list the SQL would return.
 */
fun SourceContent.acceptsSuggestionFilters(filters: Set<DesktopSuggestionFilter>): Boolean {
    if (filters.isEmpty()) return true
    return filters.all { filter ->
        when (filter) {
            is DesktopSuggestionFilter.Tag -> tags.any { it.title.trim().equals(filter.title.trim(), ignoreCase = true) }
            is DesktopSuggestionFilter.Source -> source.name == filter.name
            DesktopSuggestionFilter.Adult -> isNsfw
            DesktopSuggestionFilter.Safe -> !isNsfw
        }
    }
}

/** The suggestions the page draws: the stored order, narrowed by the chips the user picked. */
fun desktopSuggestedContents(
    items: List<SourceContent>,
    filters: Set<DesktopSuggestionFilter>,
): List<SourceContent> = if (filters.isEmpty()) items else items.filter { it.acceptsSuggestionFilters(filters) }

/**
 * The most frequent tag titles, one count per work so a work listing the same tag twice does not weight it. Ties fall
 * back to the title, which keeps the chip row stable between rebuilds that store the same works in another order.
 */
private fun topTagTitles(items: List<SourceContent>, limit: Int): List<String> {
    val spellings = LinkedHashMap<String, String>()
    val counts = HashMap<String, Int>()
    for (item in items) {
        val seen = HashSet<String>()
        for (tag in item.tags) {
            val title = tag.title.trim()
            if (title.isEmpty() || !seen.add(title.lowercase())) continue
            spellings.putIfAbsent(title.lowercase(), title)
            counts[title.lowercase()] = (counts[title.lowercase()] ?: 0) + 1
        }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .mapNotNull { spellings[it.key] }
}

/** The sources that contributed the most works, in the same count-descending, name-ascending order. */
private fun topSourceNames(items: List<SourceContent>, limit: Int): List<String> {
    val counts = LinkedHashMap<String, Int>()
    for (item in items) counts[item.source.name] = (counts[item.source.name] ?: 0) + 1
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }
}
