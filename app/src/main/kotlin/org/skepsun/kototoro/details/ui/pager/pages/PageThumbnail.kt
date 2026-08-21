package org.skepsun.kototoro.details.ui.pager.pages

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

data class PageThumbnail(
    val isCurrent: Boolean,
    val page: ReaderPage,
) : ListModel {

    val number
        get() = page.index + 1

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is PageThumbnail && page == other.page
    }
}
