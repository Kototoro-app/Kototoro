package org.skepsun.kototoro.list.domain

import org.skepsun.kototoro.core.db.ListFilterCriteria

/**
 * Maps the UI-flavoured [ListFilterOption] (resource ids, icons, display names) to the data-only
 * [ListFilterCriteria] used by shared SQL builders and list rules. Called at the repository/derivation boundary.
 */
fun ListFilterOption.toCriteria(): ListFilterCriteria = when (this) {
    ListFilterOption.Downloaded -> ListFilterCriteria.Downloaded
    is ListFilterOption.Macro -> ListFilterCriteria.Macro.valueOf(name)
    is ListFilterOption.Branch -> ListFilterCriteria.Branch(titleText = titleText, chaptersCount = chaptersCount)
    is ListFilterOption.Tag -> ListFilterCriteria.Tag(tagId = tagId, title = tag.title, key = tag.key)
    is ListFilterOption.Favorite -> ListFilterCriteria.Favorite(categoryId = category.id)
    is ListFilterOption.Source -> ListFilterCriteria.Source(mangaSource = mangaSource)
    is ListFilterOption.PublicationState -> ListFilterCriteria.PublicationState(state = state)
    is ListFilterOption.ReadingStatus -> ListFilterCriteria.ReadingStatus(statusName = status.name)
    is ListFilterOption.Inverted -> ListFilterCriteria.Inverted(option = option.toCriteria())
}

fun Collection<ListFilterOption>.toCriteria(): Set<ListFilterCriteria> = mapTo(LinkedHashSet(size)) { it.toCriteria() }
