package org.skepsun.kototoro.details.ui.pager.pages

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

data class PageThumbnail(
    val isCurrent: Boolean,
    val page: ReaderPage,
) : ListModel {

    val number
        get() = page.index + 1

    // Page ids are not unique: sources reuse the same image (and so the same id) across chapters.
    val listKey: String
        get() = "page_${page.chapterId}_${page.index}"

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is PageThumbnail && page == other.page
    }
}
