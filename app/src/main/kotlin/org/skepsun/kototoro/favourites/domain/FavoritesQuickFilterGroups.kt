package org.skepsun.kototoro.favourites.domain

import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.QuickFilterGroup

internal fun buildFavoritesQuickFilter(chips: List<ChipModel>): QuickFilter {
    val groupedChips = LinkedHashMap<FavoritesMetaFilterGroup, MutableList<ChipModel>>()
    FavoritesMetaFilterGroup.entries.forEach { groupedChips[it] = mutableListOf() }
    val autoFilters = ArrayList<ChipModel>()
    chips.forEach { chip ->
        val group = (chip.data as? ListFilterOption)?.favoritesMetaFilterGroup()
        if (group == null) {
            autoFilters += chip
        } else {
            groupedChips.getValue(group) += chip
        }
    }
    return QuickFilter(
        items = autoFilters,
        groups = FavoritesMetaFilterGroup.entries.mapNotNull { group ->
            groupedChips.getValue(group).takeIf(List<*>::isNotEmpty)?.let { items ->
                QuickFilterGroup(
                    key = group.name,
                    titleResId = group.titleResId,
                    iconResId = group.iconResId,
                    items = items,
                )
            }
        },
    )
}

private fun ListFilterOption.favoritesMetaFilterGroup(): FavoritesMetaFilterGroup? = when (this) {
    ListFilterOption.Downloaded,
    ListFilterOption.Macro.NEW_CHAPTERS,
    -> null

    ListFilterOption.Macro.COMPLETED -> FavoritesMetaFilterGroup.READING_STATUS
    ListFilterOption.Macro.NSFW -> FavoritesMetaFilterGroup.CONTENT_RATING
    ListFilterOption.Macro.MULTI_PROJECTION,
    ListFilterOption.Macro.BROKEN_PROJECTION,
    -> FavoritesMetaFilterGroup.WORK_RELATIONS

    is ListFilterOption.PublicationState -> FavoritesMetaFilterGroup.PUBLICATION_STATUS
    is ListFilterOption.ReadingStatus -> FavoritesMetaFilterGroup.READING_STATUS
    is ListFilterOption.Inverted -> if (option == ListFilterOption.Macro.NSFW) {
        FavoritesMetaFilterGroup.CONTENT_RATING
    } else {
        null
    }
    is ListFilterOption.Branch,
    is ListFilterOption.Favorite,
    is ListFilterOption.Source,
    is ListFilterOption.Tag,
    ListFilterOption.Macro.FAVORITE,
    -> null
}

private enum class FavoritesMetaFilterGroup(
    val titleResId: Int,
    val iconResId: Int,
) {
    READING_STATUS(R.string.filter_group_reading_status, R.drawable.ic_state_finished),
    PUBLICATION_STATUS(R.string.filter_group_publication_status, R.drawable.ic_state_ongoing),
    CONTENT_RATING(R.string.filter_group_content_rating, R.drawable.ic_nsfw),
    WORK_RELATIONS(R.string.filter_group_work_relations, R.drawable.ic_list_group),
}
