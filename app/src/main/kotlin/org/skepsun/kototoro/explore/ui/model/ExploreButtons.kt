package org.skepsun.kototoro.explore.ui.model

import org.skepsun.kototoro.list.ui.model.ListModel

data class ExploreButtons(
    val isRandomLoading: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is ExploreButtons
    }
}
