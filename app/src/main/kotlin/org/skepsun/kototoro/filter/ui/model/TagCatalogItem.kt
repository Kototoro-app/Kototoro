package org.skepsun.kototoro.filter.ui.model

import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentTag

data class TagCatalogItem(
    val tag: ContentTag,
    val isChecked: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is TagCatalogItem && other.tag == tag
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        return if (previousState is TagCatalogItem && previousState.isChecked != isChecked) {
            ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }
}
