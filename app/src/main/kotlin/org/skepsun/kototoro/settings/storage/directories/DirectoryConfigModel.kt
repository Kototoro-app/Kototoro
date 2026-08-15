package org.skepsun.kototoro.settings.storage.directories

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.local.data.LocalStorageRoot

data class DirectoryConfigModel(
    val title: String,
    val root: LocalStorageRoot,
    val isDefault: Boolean,
    val isAppPrivate: Boolean,
    val isAccessible: Boolean,
    val size: Long,
    val available: Long?,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is DirectoryConfigModel && root == other.root
    }
}
