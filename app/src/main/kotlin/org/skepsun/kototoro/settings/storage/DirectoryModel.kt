package org.skepsun.kototoro.settings.storage

import androidx.annotation.StringRes
import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.local.data.LocalStorageRoot

data class DirectoryModel(
    val title: String?,
    @StringRes val titleRes: Int,
    val root: LocalStorageRoot?,
    val isRemovable: Boolean,
    val isChecked: Boolean,
    val isAvailable: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is DirectoryModel && other.root == root && other.title == title && other.titleRes == titleRes
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        return if (previousState is DirectoryModel && previousState.isChecked != isChecked) {
            ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }
}
