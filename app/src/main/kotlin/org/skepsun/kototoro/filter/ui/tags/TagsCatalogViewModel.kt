package org.skepsun.kototoro.filter.ui.tags

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.filter.ui.model.TagCatalogItem
import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorFooter
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.ui.model.ListHeader

@HiltViewModel(assistedFactory = TagsCatalogViewModel.Factory::class)
class TagsCatalogViewModel @AssistedInject constructor(
    @Assisted private val filter: FilterCoordinator,
    @Assisted private val isExcluded: Boolean,
    @Assisted private val groupTitle: String?,
    private val mangaDataRepository: ContentDataRepository,
) : BaseViewModel() {

    val searchQuery = MutableStateFlow("")

    private val filterProperty: StateFlow<FilterProperty<UiTagGroup>>
        get() = if (isExcluded) filter.tagsExcluded else filter.tags

    val sheetTitle: String?
        get() = groupTitle

    @Suppress("RemoveExplicitTypeArguments")
    private val tags: StateFlow<List<ListModel>> = combine(
        filter.getAllTagGroups(),
        flow<Collection<ContentTag>> { emit(emptyList()); emit(mangaDataRepository.findTags(filter.mangaSource)) },
        filterProperty,
    ) { available, cached, property ->
        val selected = property.selectedItems.flatMap { it.selected }.toSet()
        buildList(available, cached, selected)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    val content = combine(tags, searchQuery) { raw, query ->
        filterByQuery(raw, query)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

    fun handleTagClick(tag: ContentTag, isChecked: Boolean) {
        if (isExcluded) {
            filter.toggleTagExclude(tag, !isChecked)
        } else {
            filter.toggleTag(tag, !isChecked)
        }
    }

    private fun buildList(
        available: Result<List<UiTagGroup>>,
        cached: Collection<ContentTag>,
        selected: Set<ContentTag>,
    ): List<ListModel> {
        val locale = filter.mangaSource.getLocale()?.language
        val comparator = TagTitleComparator(locale)
        val result = ArrayList<ListModel>()

        available.getOrNull()
            .orEmpty()
            .filter { groupTitle.isNullOrBlank() || it.title == groupTitle }
            .forEach { group ->
            val tags = group.tags.sortedWith(comparator)
            if (tags.isEmpty()) return@forEach
            if (groupTitle.isNullOrBlank()) {
                result.add(ListHeader(group.title))
            }
            tags.forEach { tag ->
                result.add(
                    TagCatalogItem(
                        tag = tag,
                        isChecked = tag in selected,
                    ),
                )
            }
        }

        if (result.isEmpty()) {
            val extra = cached.sortedWith(comparator)
            if (groupTitle.isNullOrBlank() && extra.isNotEmpty()) {
                result.add(ListHeader(R.string.other_tags))
                extra.forEach { tag ->
                    result.add(TagCatalogItem(tag, tag in selected))
                }
            }
        }

        available.exceptionOrNull()?.let { error ->
            result.add(
                if (result.isEmpty()) {
                    error.toErrorState(canRetry = false)
                } else {
                    error.toErrorFooter()
                },
            )
        }
        return result
    }

    private fun filterByQuery(list: List<ListModel>, query: String): List<ListModel> {
        if (query.isBlank()) return list
        val filtered = ArrayList<ListModel>(list.size)
        var currentHeader: ListHeader? = null
        var hasMatchInSection = false
        list.forEach { item ->
            when (item) {
                is ListHeader -> {
                    if (hasMatchInSection) {
                        currentHeader?.let { filtered.add(it) }
                    }
                    currentHeader = item
                    hasMatchInSection = false
                }
                is TagCatalogItem -> {
                    if (item.tag.title.contains(query, ignoreCase = true) || item.isChecked) {
                        if (!hasMatchInSection) {
                            currentHeader?.let { filtered.add(it) }
                        }
                        filtered.add(item)
                        hasMatchInSection = true
                    }
                }
            }
        }
        return filtered
    }

    @AssistedFactory
    interface Factory {
        fun create(filter: FilterCoordinator, isExcludeTag: Boolean, groupTitle: String?): TagsCatalogViewModel
    }

}
