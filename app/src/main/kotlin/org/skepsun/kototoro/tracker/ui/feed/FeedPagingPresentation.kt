package org.skepsun.kototoro.tracker.ui.feed

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.calculateDateGroup
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem

internal fun PagingData<FeedItem>.applyFeedPagingPresentation(): PagingData<ListModel> =
    insertSeparators { before: FeedItem?, after: FeedItem? ->
        val beforeHeader = before?.dateHeader()
        val afterHeader = after?.dateHeader()
        afterHeader?.takeIf { before == null || beforeHeader != afterHeader }
    }

private fun FeedItem.dateHeader(): ListHeader = calculateDateGroup(createdAt)?.let(::ListHeader)
    ?: ListHeader(R.string.unknown)
