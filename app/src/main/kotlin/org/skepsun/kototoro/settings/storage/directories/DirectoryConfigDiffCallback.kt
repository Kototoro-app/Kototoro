package org.skepsun.kototoro.settings.storage.directories

import androidx.recyclerview.widget.DiffUtil.ItemCallback

class DirectoryConfigDiffCallback : ItemCallback<DirectoryConfigModel>() {

    override fun areItemsTheSame(oldItem: DirectoryConfigModel, newItem: DirectoryConfigModel): Boolean {
        return oldItem.root == newItem.root
    }

    override fun areContentsTheSame(oldItem: DirectoryConfigModel, newItem: DirectoryConfigModel): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: DirectoryConfigModel, newItem: DirectoryConfigModel): Any? {
        return if (oldItem.isDefault != newItem.isDefault) {
            Unit
        } else {
            super.getChangePayload(oldItem, newItem)
        }
    }
}
