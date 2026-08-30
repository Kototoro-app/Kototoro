package org.skepsun.kototoro.list.domain

import androidx.collection.ArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.model.toChipModel
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.parsers.util.suspendlazy.getOrNull
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy

abstract class ContentListQuickFilter(
    private val settings: AppSettings,
) : QuickFilterListener {

    private val appliedFilter = MutableStateFlow<Set<ListFilterOption>>(emptySet())
    private val availableFilterOptions = suspendLazy {
        getAvailableFilterOptions()
    }

    open val appliedOptions
        get() = appliedFilter.asStateFlow()

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
        appliedFilter.value = ArraySet(appliedFilter.value).also {
            if (isApplied) {
                it.addNoConflicts(option)
            } else {
                it.remove(option)
            }
        }
    }

    override fun toggleFilterOption(option: ListFilterOption) {
        appliedFilter.value = ArraySet(appliedFilter.value).also {
            if (option in it) {
                it.remove(option)
            } else {
                it.addNoConflicts(option)
            }
        }
    }

    override fun clearFilter() {
        appliedFilter.value = emptySet()
    }

    suspend fun filterItem(
        selectedOptions: Set<ListFilterOption>,
        ignoreVisibilitySetting: Boolean = false,
    ): QuickFilter? {
        if (!ignoreVisibilitySetting && !settings.isQuickFilterEnabled) {
            return null
        }
        val availableOptions = availableFilterOptions.getOrNull().orEmpty()
        val selectedOptionsOnly = selectedOptions.filterNot { it in availableOptions }
        val chips = buildList {
            selectedOptionsOnly.mapTo(this) { option ->
                option.toChipModel(isChecked = true)
            }
            availableOptions.mapTo(this) { option ->
                option.toChipModel(isChecked = option in selectedOptions)
            }
        }
        return if (chips.isNotEmpty()) {
            createFilterModel(chips)
        } else {
            null
        }
    }

    protected open fun createFilterModel(chips: List<org.skepsun.kototoro.core.ui.widgets.ChipModel>) =
        QuickFilter(chips)

    protected abstract suspend fun getAvailableFilterOptions(): List<ListFilterOption>

    private fun ArraySet<ListFilterOption>.addNoConflicts(option: ListFilterOption) {
        add(option)
        if (option is ListFilterOption.Inverted) {
            remove(option.option)
        } else {
            removeIf { it is ListFilterOption.Inverted && it.option == option }
        }
    }
}
