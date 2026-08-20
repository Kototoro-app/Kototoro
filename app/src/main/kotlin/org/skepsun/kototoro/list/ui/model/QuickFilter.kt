package org.skepsun.kototoro.list.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.list.ui.ListModelDiffCallback

data class QuickFilter(
	val items: List<ChipModel>,
	val groups: List<QuickFilterGroup> = emptyList(),
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is QuickFilter

	override fun getChangePayload(previousState: ListModel) = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}

data class QuickFilterGroup(
	val key: String,
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
	val items: List<ChipModel>,
)
