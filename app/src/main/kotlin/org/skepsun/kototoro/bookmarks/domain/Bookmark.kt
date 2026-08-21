package org.skepsun.kototoro.bookmarks.domain

import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.isImage
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentPage
import java.time.Instant

data class Bookmark(
    val manga: Content,
    val pageId: Long,
    val chapterId: Long,
    val chapterTitle: String? = null,
    val page: Int,
    val scroll: Int,
    val imageUrl: String,
    val createdAt: Instant,
    val percent: Float,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is Bookmark &&
            manga.id == other.manga.id &&
            chapterId == other.chapterId &&
            page == other.page
    }

    fun toContentPage() = ContentPage(
        id = pageId,
        url = imageUrl,
        preview = imageUrl.takeIf {
            MimeTypes.getMimeTypeFromUrl(it)?.isImage == true
        },
        source = manga.source,
    )
}
