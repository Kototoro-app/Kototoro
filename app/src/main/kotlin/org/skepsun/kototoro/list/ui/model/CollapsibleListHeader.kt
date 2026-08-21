package org.skepsun.kototoro.list.ui.model

import android.content.Context
import androidx.annotation.StringRes

/**
 * A collapsible header for grouping list items
 *
 * @param text The header text
 * @param isCollapsible Whether this header can be collapsed/expanded
 * @param isExpanded Current expansion state (only relevant if isCollapsible is true)
 * @param groupId Unique identifier for this group
 */
data class CollapsibleListHeader(
    val text: CharSequence,
    val isCollapsible: Boolean,
    val isExpanded: Boolean = true,
    val groupId: String,
) : ListModel {

    constructor(
        @StringRes textRes: Int,
        context: Context,
        isCollapsible: Boolean,
        isExpanded: Boolean = true,
        groupId: String,
    ) : this(
        text = context.getString(textRes),
        isCollapsible = isCollapsible,
        isExpanded = isExpanded,
        groupId = groupId
    )

    fun getText(context: Context): CharSequence = text

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is CollapsibleListHeader && groupId == other.groupId
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        if (previousState !is CollapsibleListHeader) {
            return super.getChangePayload(previousState)
        }
        return if (isExpanded != previousState.isExpanded) {
            isExpanded
        } else {
            super.getChangePayload(previousState)
        }
    }
}
