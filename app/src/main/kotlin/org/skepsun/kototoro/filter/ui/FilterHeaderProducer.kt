package org.skepsun.kototoro.filter.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.ui.model.FilterHeaderModel
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.parsers.util.toTitleCase
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

class FilterHeaderProducer @Inject constructor(
    private val searchRepository: ContentSearchRepository,
) {

    fun observeHeader(filterCoordinator: FilterCoordinator): Flow<FilterHeaderModel> {
        return combine(
            filterCoordinator.savedFilters,
            filterCoordinator.tags,
            filterCoordinator.observe(),
        ) { saved, tags, snapshot ->
            val chipList = createChipsList(
                source = filterCoordinator.mangaSource,
                capabilities = filterCoordinator.capabilities,
                savedFilters = saved,
                tagsProperty = tags,
                snapshot = snapshot.listFilter,
                limit = 12,
            )
            FilterHeaderModel(
                chips = chipList,
                sortOrder = snapshot.sortOrder,
                isFilterApplied = !snapshot.listFilter.isEmpty(),
            )
        }
    }

    private suspend fun createChipsList(
        source: ContentSource,
        capabilities: ContentListFilterCapabilities,
        savedFilters: FilterProperty<PersistableFilter>,
        tagsProperty: FilterProperty<UiTagGroup>,
        snapshot: ContentListFilter,
        limit: Int,
    ): List<ChipModel> {
        val result = ArrayDeque<ChipModel>(savedFilters.availableItems.size + limit + 3)
        if (snapshot.query.isNullOrEmpty() || capabilities.isSearchWithFiltersSupported) {
            val selectedTags = snapshot.tags.toMutableSet()
            // 扁平化选中分组
            val groupedTags = tagsProperty.availableItems.flatMap { it.tags }
            var tags = if (selectedTags.isEmpty()) {
                searchRepository.getTagsSuggestion("", limit, source)
            } else {
                searchRepository.getTagsSuggestion(selectedTags).take(limit)
            }
            if (tags.size < limit) {
                val remaining = groupedTags.filterNot { tags.contains(it) || selectedTags.contains(it) }
                tags = tags + remaining.take(limit - tags.size)
            }
            if (tags.isEmpty() && selectedTags.isEmpty()) {
                return emptyList()
            }
            for (saved in savedFilters.availableItems) {
                val model = ChipModel(
                    title = saved.name,
                    isChecked = saved in savedFilters.selectedItems,
                    data = saved,
                )
                if (model.isChecked) {
                    selectedTags.removeAll(saved.filter.tags)
                    result.addFirst(model)
                } else {
                    result.addLast(model)
                }
            }
            for (tag in tags) {
                val model = ChipModel(
                    title = tag.title,
                    isChecked = selectedTags.remove(tag),
                    data = tag,
                )
                if (model.isChecked) {
                    result.addFirst(model)
                } else {
                    result.addLast(model)
                }
            }
            for (tag in selectedTags) {
                val model = ChipModel(
                    title = tag.title,
                    isChecked = true,
                    data = tag,
                )
                result.addFirst(model)
            }
        }
        snapshot.locale?.let {
            result.addFirst(
                ChipModel(
                    title = it.getDisplayName(it).toTitleCase(it),
                    icon = R.drawable.ic_language,
                    isCloseable = true,
                    data = it,
                ),
            )
        }
        snapshot.types.forEach {
            result.addFirst(
                ChipModel(
                    titleResId = it.titleResId,
                    isCloseable = true,
                    data = it,
                ),
            )
        }
        snapshot.demographics.forEach {
            result.addFirst(
                ChipModel(
                    titleResId = it.titleResId,
                    isCloseable = true,
                    data = it,
                ),
            )
        }
        snapshot.contentRating.forEach {
            result.addFirst(
                ChipModel(
                    titleResId = it.titleResId,
                    isCloseable = true,
                    data = it,
                ),
            )
        }
        snapshot.states.forEach {
            result.addFirst(
                ChipModel(
                    titleResId = it.titleResId,
                    isCloseable = true,
                    data = it,
                ),
            )
        }
        if (!snapshot.query.isNullOrEmpty()) {
            result.addFirst(
                ChipModel(
                    title = snapshot.query,
                    icon = appcompatR.drawable.abc_ic_search_api_material,
                    isCloseable = true,
                    data = snapshot.query,
                ),
            )
        }
        if (!snapshot.author.isNullOrEmpty()) {
            result.addFirst(
                ChipModel(
                    title = snapshot.author,
                    icon = R.drawable.ic_user,
                    isCloseable = true,
                    data = snapshot.author,
                ),
            )
        }
        val hasTags = result.any { it.data is UiTagGroup || it.data is String }
        if (hasTags) {
            result.addFirst(moreTagsChip())
        }
        return result
    }

    private fun moreTagsChip() = ChipModel(
        titleResId = R.string.genres,
        icon = R.drawable.ic_drawer_menu_open,
    )
}
