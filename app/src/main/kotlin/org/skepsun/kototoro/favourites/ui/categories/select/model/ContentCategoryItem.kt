package org.skepsun.kototoro.favourites.ui.categories.select.model

import com.google.android.material.checkbox.MaterialCheckBox.CheckedState
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel

data class ContentCategoryItem(
    val category: FavouriteCategory,
    @CheckedState val checkedState: Int,
    val isTrackerEnabled: Boolean,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is ContentCategoryItem && other.category.id == category.id
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        return if (previousState is ContentCategoryItem && previousState.checkedState != checkedState) {
            ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }
}
