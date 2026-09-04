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

internal fun contentListItemKey(
    item: ListModel?,
    index: Int,
): Any = when (item) {
    is ContentGridModel -> item.id
    is ContentCompactListModel -> item.id
    is ContentDetailedListModel -> item.id
    is ListHeader -> "header:${item.hashCode()}:$index"
    is QuickFilter -> "quick_filter:$index"
    is InfoModel -> "info:${item.hashCode()}:$index"
    is EmptyState -> "empty:${item.hashCode()}:$index"
    is ErrorState -> "error:${item.hashCode()}:$index"
    LoadingState -> "loading:$index"
    null -> "paging_placeholder:$index"
    is ContentListModel -> item.id
    else -> "${item.javaClass.name}:${item.hashCode()}:$index"
}

internal fun contentListItemContentType(
    item: ListModel?,
): ContentListItemType = when (item) {
    is ContentGridModel -> ContentListItemType.GRID_CARD
    is ContentCompactListModel -> ContentListItemType.COMPACT_CARD
    is ContentDetailedListModel -> ContentListItemType.DETAILED_CARD
    is ListHeader -> ContentListItemType.HEADER
    is QuickFilter -> ContentListItemType.QUICK_FILTER
    is InfoModel -> ContentListItemType.INFO
    is EmptyState -> ContentListItemType.EMPTY
    is ErrorState -> ContentListItemType.ERROR
    LoadingState -> ContentListItemType.LOADING
    null -> ContentListItemType.PLACEHOLDER
    is ContentListModel -> ContentListItemType.OTHER
    else -> ContentListItemType.OTHER
}

internal fun contentListItemDescriptor(
    item: ListModel?,
    index: Int,
): ContentListItemDescriptor = ContentListItemDescriptor(
    key = contentListItemKey(item, index),
    contentType = contentListItemContentType(item),
)

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
