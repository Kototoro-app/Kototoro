package org.skepsun.kototoro.discover.ui.model

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory

data class DiscoverCarouselRow(
    val category: TrackingSiteCategory,
    val items: List<ListModel>,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is DiscoverCarouselRow && other.category.id == category.id
    }
}
