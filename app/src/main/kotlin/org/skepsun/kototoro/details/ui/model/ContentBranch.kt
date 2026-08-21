package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel

data class ContentBranch(
    val name: String?,
    val count: Int,
    val isSelected: Boolean,
    val isCurrent: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is ContentBranch && other.name == name
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        return if (previousState is ContentBranch && previousState.isSelected != isSelected) {
            ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }

    override fun toString(): String {
        return "$name: $count"
    }
}
