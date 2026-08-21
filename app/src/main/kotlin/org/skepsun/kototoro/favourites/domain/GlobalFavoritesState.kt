package org.skepsun.kototoro.favourites.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalFavoritesState @Inject constructor(
    private val settings: AppSettings
) {

    // Quick Filter Options (Downloaded, Completed, etc.)
    private val _appliedFilter = MutableStateFlow<Set<ListFilterOption>>(emptySet())
    val appliedFilter = _appliedFilter.asStateFlow()

    fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
        val current = _appliedFilter.value.toMutableSet()
        if (isApplied) {
            addNoConflicts(current, option)
        } else {
            current.remove(option)
        }
        _appliedFilter.value = current
    }

    fun toggleFilterOption(option: ListFilterOption) {
        val current = _appliedFilter.value.toMutableSet()
        if (option in current) {
            current.remove(option)
        } else {
            addNoConflicts(current, option)
        }
        _appliedFilter.value = current
    }

    fun clearFilter() {
        _appliedFilter.value = emptySet()
    }

    private fun addNoConflicts(set: MutableSet<ListFilterOption>, option: ListFilterOption) {
        set.add(option)
        if (option is ListFilterOption.Inverted) {
            set.remove(option.option)
        } else {
            set.removeIf { it is ListFilterOption.Inverted && it.option == option }
        }
    }

    // Content Type (All, Content, Novel)
    private val _selectedGroupTab = MutableStateFlow<BrowseGroupTab>(
        BrowseGroupTab.fromId(settings.getSelectedGroupTab() ?: BrowseGroupTab.All.id)
    )
    val selectedGroupTab = _selectedGroupTab.asStateFlow()

    fun setSelectedGroupTab(tab: BrowseGroupTab) {
        if (_selectedGroupTab.value == tab) return
        _selectedGroupTab.value = tab
        settings.setSelectedGroupTab(tab.id)
    }

    fun clearSelectedGroupTab() {
        if (_selectedGroupTab.value == BrowseGroupTab.All) return
        _selectedGroupTab.value = BrowseGroupTab.All
        settings.setSelectedGroupTab(BrowseGroupTab.All.id)
    }

    // Source Tags (Mihon, Local, etc.)
    private val _selectedSourceTags = MutableStateFlow<Set<SourceTag>>(
        SourceTag.sanitizeQuickFilterSelection(SourceTag.fromIds(settings.getSelectedSourceTags()))
    )
    val selectedSourceTags = _selectedSourceTags.asStateFlow()

    fun setSelectedSourceTags(tags: Set<SourceTag>) {
        val sanitizedTags = SourceTag.sanitizeQuickFilterSelection(tags)
        if (_selectedSourceTags.value == sanitizedTags) return
        _selectedSourceTags.value = sanitizedTags
        settings.setSelectedSourceTags(sanitizedTags.map { it.id }.toSet())
    }

    fun toggleSourceTag(tag: SourceTag) {
        val current = _selectedSourceTags.value
        val next = if (current.contains(tag)) emptySet() else setOf(tag)
        setSelectedSourceTags(next)
    }

    fun clearSourceTags() {
        _selectedSourceTags.value = emptySet()
        settings.setSelectedSourceTags(emptySet())
    }

    fun resetFilters(clearGroupTab: Boolean) {
        clearFilter()
        clearSourceTags()
        if (clearGroupTab) {
            clearSelectedGroupTab()
        }
    }
}
