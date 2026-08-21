package org.skepsun.kototoro.discover.ui.model

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem

data class DiscoverItem(
    val item: TrackingSiteItem,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is DiscoverItem &&
            other.item.service == item.service &&
            other.item.remoteId == item.remoteId
    }
}
