package org.skepsun.kototoro.search.ui.multi

import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.ui.model.ButtonFooter
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState

data class SearchResultsState(
    val sections: List<SearchResultsListModel> = emptyList(),
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val canSearchDisabledSources: Boolean = true,
    val retryingSectionKeys: Set<String> = emptySet(),
) {
    val failedSources: Int
        get() = sections.count { it.error != null }

    internal fun withResult(
        result: SearchResultsListModel?,
        replacedSection: SearchResultsListModel? = null,
    ): SearchResultsState {
        if (result == null && replacedSection == null) return this
        val updated = (if (isRefreshing) emptyList() else sections).toMutableList()
        val key = (replacedSection ?: result)?.sectionKey
        val index = updated.indexOfFirst { it.sectionKey == key }
        when {
            result == null && index >= 0 -> updated.removeAt(index)
            result != null && index >= 0 -> updated[index] = result
            result != null -> updated.add(result)
        }
        return copy(sections = updated, isRefreshing = false)
    }

    internal fun finished(): SearchResultsState = copy(
        sections = if (isRefreshing) emptyList() else sections,
        isSearching = false,
        isRefreshing = false,
        retryingSectionKeys = emptySet(),
    )

    internal fun asListModels(hideEmpty: Boolean): List<ListModel> {
        val visible = if (hideEmpty) sections.filter { it.list.isNotEmpty() || it.error != null } else sections
        if (visible.isEmpty()) {
            return listOf(
                if (isSearching) LoadingState else EmptyState(
                    icon = R.drawable.ic_empty_common,
                    textPrimary = R.string.nothing_found,
                    textSecondary = R.string.text_search_holder_secondary,
                    actionStringRes = if (canSearchDisabledSources) R.string.search_disabled_sources else 0,
                ),
            )
        }
        return when {
            isSearching -> visible + LoadingFooter()
            canSearchDisabledSources -> visible + ButtonFooter(R.string.search_disabled_sources)
            else -> visible
        }
    }
}

internal val SearchResultsListModel.sectionKey: String
    get() = "${source.name}:$titleResId"
