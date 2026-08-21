package org.skepsun.kototoro.settings.sources.catalog

import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentType

data class SourceCatalogPage(
    val type: ContentType,
    val items: List<SourceCatalogItem>,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is SourceCatalogPage && other.type == type
    }

    override fun getChangePayload(previousState: ListModel): Any {
        return ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
    }
}
