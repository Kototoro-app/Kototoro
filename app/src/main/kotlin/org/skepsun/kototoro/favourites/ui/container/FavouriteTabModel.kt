package org.skepsun.kototoro.favourites.ui.container

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.domain.ListSortOrder

data class FavouriteTabModel(
	val id: Long,
	val title: String?,
	val order: ListSortOrder? = null,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FavouriteTabModel && other.id == id
	}
}
