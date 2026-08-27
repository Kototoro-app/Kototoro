package org.skepsun.kototoro.list.ui.compose

import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.InfoModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter

internal enum class ContentListItemType {
    GRID_CARD,
    COMPACT_CARD,
    DETAILED_CARD,
    HEADER,
    QUICK_FILTER,
    INFO,
    EMPTY,
    ERROR,
    LOADING,
    OTHER,
    PLACEHOLDER,
}

internal data class ContentListItemDescriptor(
    val key: Any,
    val contentType: ContentListItemType,
)

internal fun contentListItemDescriptor(
    item: ListModel?,
    index: Int,
): ContentListItemDescriptor = when (item) {
    is ContentGridModel -> ContentListItemDescriptor(item.id, ContentListItemType.GRID_CARD)
    is ContentCompactListModel -> ContentListItemDescriptor(item.id, ContentListItemType.COMPACT_CARD)
    is ContentDetailedListModel -> ContentListItemDescriptor(item.id, ContentListItemType.DETAILED_CARD)
    is ListHeader -> ContentListItemDescriptor("header:${item.hashCode()}:$index", ContentListItemType.HEADER)
    is QuickFilter -> ContentListItemDescriptor("quick_filter:$index", ContentListItemType.QUICK_FILTER)
    is InfoModel -> ContentListItemDescriptor("info:${item.hashCode()}:$index", ContentListItemType.INFO)
    is EmptyState -> ContentListItemDescriptor("empty:${item.hashCode()}:$index", ContentListItemType.EMPTY)
    is ErrorState -> ContentListItemDescriptor("error:${item.hashCode()}:$index", ContentListItemType.ERROR)
    LoadingState -> ContentListItemDescriptor("loading:$index", ContentListItemType.LOADING)
    null -> ContentListItemDescriptor("paging_placeholder:$index", ContentListItemType.PLACEHOLDER)
    is ContentListModel -> ContentListItemDescriptor(item.id, ContentListItemType.OTHER)
    else -> ContentListItemDescriptor("${item.javaClass.name}:${item.hashCode()}:$index", ContentListItemType.OTHER)
}

internal sealed interface ContentListItemOrigin {
    data class Leading(val index: Int) : ContentListItemOrigin
    data class Paging(val index: Int) : ContentListItemOrigin
    data object OutOfBounds : ContentListItemOrigin
}

internal class CombinedContentListIndex(
    private val leadingCount: Int,
    private val pagingCount: Int,
) {
    val itemCount: Int = leadingCount + pagingCount

    fun origin(
        index: Int,
        availablePagingCount: Int = pagingCount,
    ): ContentListItemOrigin = when {
        index < 0 || index >= leadingCount + minOf(pagingCount, availablePagingCount.coerceAtLeast(0)) ->
            ContentListItemOrigin.OutOfBounds
        index < leadingCount -> ContentListItemOrigin.Leading(index)
        else -> ContentListItemOrigin.Paging(index - leadingCount)
    }
}
