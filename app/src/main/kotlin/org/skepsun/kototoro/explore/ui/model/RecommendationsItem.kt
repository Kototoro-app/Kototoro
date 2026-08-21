package org.skepsun.kototoro.explore.ui.model

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel

data class RecommendationsItem(
    val manga: List<ContentCompactListModel>
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is RecommendationsItem
    }
}
