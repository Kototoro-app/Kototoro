package org.skepsun.kototoro.list.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.core.extensions.SourceEmptyHintKind

data class EmptyHint(
    @DrawableRes val icon: Int,
    @StringRes val textPrimary: Int,
    @StringRes val textSecondary: Int,
    @StringRes val actionStringRes: Int,
    /**
     * Why the page is empty, so the renderer can route its action button: an empty library sends the
     * user to the extension manager, an over-filtered page offers to reset the filters.
     */
    val kind: SourceEmptyHintKind = SourceEmptyHintKind.NONE,
) : ListModel {

    fun toState() = EmptyState(icon, textPrimary, textSecondary, actionStringRes)

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is EmptyHint && textPrimary == other.textPrimary
    }
}
