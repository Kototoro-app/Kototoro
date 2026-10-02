package org.skepsun.kototoro.list.domain

import org.skepsun.kototoro.core.db.ListFilterCriteria

/**
 * Maps the UI-flavoured [ListFilterOption] (resource ids, icons, display names) to the data-only
 * [ListFilterCriteria] the shared database layer builds SQL from. Called at the repository boundary.
 */
fun ListFilterOption.toCriteria(): ListFilterCriteria = when (this) {
    ListFilterOption.Downloaded -> ListFilterCriteria.Downloaded
    is ListFilterOption.Macro -> ListFilterCriteria.Macro.valueOf(name)
    is ListFilterOption.Branch -> ListFilterCriteria.Branch(titleText = titleText, chaptersCount = chaptersCount)
    is ListFilterOption.Tag -> ListFilterCriteria.Tag(tagId = tagId)
    is ListFilterOption.Favorite -> ListFilterCriteria.Favorite(categoryId = category.id)
    is ListFilterOption.Source -> ListFilterCriteria.Source(mangaSource = mangaSource)
    is ListFilterOption.PublicationState -> ListFilterCriteria.PublicationState(state = state)
    is ListFilterOption.ReadingStatus -> ListFilterCriteria.ReadingStatus(statusName = status.name)
    is ListFilterOption.Inverted -> ListFilterCriteria.Inverted(option = option.toCriteria())
}

fun Collection<ListFilterOption>.toCriteria(): Set<ListFilterCriteria> = mapTo(LinkedHashSet(size)) { it.toCriteria() }
