package org.skepsun.kototoro.explore.ui.model

import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.util.longHashCode

data class ContentSourceItem(
	val source: ContentSourceInfo,
	val isGrid: Boolean,
) : ListModel {

	val id: Long = source.name.longHashCode()

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ContentSourceItem && other.source.name == source.name
	}
}
