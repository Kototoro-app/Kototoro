package org.skepsun.kototoro.list.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class EmptyState(
    @DrawableRes val icon: Int,
    @StringRes val textPrimary: Int,
    @StringRes val textSecondary: Int,
    @StringRes val actionStringRes: Int,
    val textPrimaryText: CharSequence? = null,
    val textSecondaryText: CharSequence? = null,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is EmptyState
    }
}
