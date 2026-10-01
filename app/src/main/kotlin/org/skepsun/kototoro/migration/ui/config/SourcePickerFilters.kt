package org.skepsun.kototoro.migration.ui.config

import java.text.Collator
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.parsers.model.ContentSource

internal enum class SourceSelectionFilter { ALL, SELECTED, UNSELECTED }

internal data class SourcePickerFilters(
    val query: String = "",
    val selection: SourceSelectionFilter = SourceSelectionFilter.ALL,
    val pinnedOnly: Boolean = false,
    val language: String? = null,
    val type: SourceType? = null,
    val alphabetical: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(selection != SourceSelectionFilter.ALL, pinnedOnly, language != null, type != null).count { it }
}

internal data class SourcePickerEntry(
    val source: ContentSource,
    val title: String,
    val language: String,
    val type: SourceType,
) {
    // Non-Latin names and numbers share a truthful fallback rather than a guessed transliteration.
    val initial: String
        get() = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
}

internal fun filterPickerEntries(
    entries: List<SourcePickerEntry>,
    filters: SourcePickerFilters,
    selected: Set<String>,
    pinned: Set<String>,
): List<SourcePickerEntry> {
    val query = filters.query.trim()
    val filtered = entries.filter { entry ->
        val name = entry.source.name
        (query.isEmpty() || entry.title.contains(query, ignoreCase = true) ||
            name.contains(query, ignoreCase = true)) &&
            (filters.language == null || entry.language == filters.language) &&
            (filters.type == null || entry.type == filters.type) &&
            (!filters.pinnedOnly || name in pinned) &&
            when (filters.selection) {
                SourceSelectionFilter.ALL -> true
                SourceSelectionFilter.SELECTED -> name in selected
                SourceSelectionFilter.UNSELECTED -> name !in selected
            }
    }
    if (!filters.alphabetical) return filtered
    val collator = Collator.getInstance()
    return filtered.sortedWith(
        compareBy<SourcePickerEntry> { it.initial == "#" }
            .thenComparator { left, right -> collator.compare(left.title, right.title) }
            .thenBy { it.source.name },
    )
}

/** Bulk actions change only visible sources and keep the search order of every existing selection. */
internal fun FamilySources.withPreset(preset: SourcePreset, visibleNames: List<String>? = null): FamilySources {
    val availableNames = available.mapTo(HashSet()) { it.name }
    val scope = (visibleNames ?: available.map { it.name }).filter { it in availableNames }.distinct()
    val wanted = when (preset) {
        SourcePreset.ALL, SourcePreset.ENABLED -> scope
        SourcePreset.PINNED -> scope.filter { it in pinned }
        SourcePreset.NONE -> emptyList()
    }
    if (visibleNames == null) return copy(selected = wanted)
    val scopeSet = scope.toHashSet()
    val wantedSet = wanted.toHashSet()
    val kept = selected.filter { it !in scopeSet || it in wantedSet }
    val keptSet = kept.toHashSet()
    return copy(selected = kept + wanted.filter { it !in keptSet })
}
