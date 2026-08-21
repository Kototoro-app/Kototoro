package org.skepsun.kototoro.details.ui.pager.pages

import org.skepsun.kototoro.list.ui.model.ListModel

data class PageThumbnailPlaceholder(
    val chapterId: Long,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is PageThumbnailPlaceholder && chapterId == other.chapterId
    }
}
