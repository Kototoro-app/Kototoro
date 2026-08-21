package org.skepsun.kototoro.search.ui.multi

import android.content.Context
import androidx.annotation.StringRes
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder

data class SearchResultsListModel(
    @StringRes val titleResId: Int,
    val source: ContentSource,
    val listFilter: ContentListFilter?,
    val sortOrder: SortOrder?,
    val list: List<ContentListModel>,
    val error: Throwable?,
) : ListModel {

    fun getTitle(context: Context): String = if (titleResId != 0) {
        context.getString(titleResId)
    } else {
        source.getTitle(context)
    }

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is SearchResultsListModel && source == other.source && titleResId == other.titleResId
    }

    override fun getChangePayload(previousState: ListModel): Any? {
        return if (previousState is SearchResultsListModel && previousState.list != list) {
            ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
        } else {
            super.getChangePayload(previousState)
        }
    }
}
