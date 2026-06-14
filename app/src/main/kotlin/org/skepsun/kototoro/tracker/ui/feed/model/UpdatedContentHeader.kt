package org.skepsun.kototoro.tracker.ui.feed.model

import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentListModel

data class UpdatedContentHeaderItem(
	val model: ContentListModel,
	val groupKey: Long,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
	val totalNewChapters: Int,
)

data class UpdatedContentHeader(
	val list: List<UpdatedContentHeaderItem>,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is UpdatedContentHeader
	}

	override fun getChangePayload(previousState: ListModel): Any {
		return ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
	}
}
