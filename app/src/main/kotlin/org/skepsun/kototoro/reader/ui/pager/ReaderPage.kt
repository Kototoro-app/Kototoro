package org.skepsun.kototoro.reader.ui.pager

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlinx.parcelize.RawValue
import org.skepsun.kototoro.core.model.parcelable.ContentSourceParceler
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.longHashCode

enum class ReaderPageSplit {
    NONE, LEFT, RIGHT
}

@Parcelize
@TypeParceler<ContentSource, ContentSourceParceler>
data class ReaderPage(
    val id: Long,
    val url: String,
    val preview: String?,
    val headers: @RawValue Map<String, String>?,
    val chapterId: Long,
    val index: Int,
    val source: ContentSource,
    val split: ReaderPageSplit = ReaderPageSplit.NONE,
    val reloadNonce: Long = 0L,
) : Parcelable {

    val readerKey: Long
        get() = "$chapterId#$index#$url#${split.name}#$reloadNonce".longHashCode()

    /**
     * A synthetic page the paged readers show after the last page of [chapterId] when chapter break cards are on.
     * It has no image and never takes part in reading progress; its titles come from the chapter list.
     */
    val isChapterBreak: Boolean
        get() = url == CHAPTER_BREAK_URL

    constructor(page: ContentPage, index: Int, chapterId: Long) : this(
        id = page.id,
        url = page.url,
        preview = page.preview,
        headers = page.headers,
        chapterId = chapterId,
        index = index,
        source = page.source,
        reloadNonce = 0L,
    )

    fun toContentPage() = ContentPage(
        id = id,
        url = url,
        preview = preview,
        headers = headers,
        source = source,
    )

    companion object {

        const val CHAPTER_BREAK_URL = "kototoro://chapter-break"

        fun chapterBreak(afterChapterId: Long, source: ContentSource) = ReaderPage(
            id = "break#$afterChapterId".longHashCode(),
            url = CHAPTER_BREAK_URL,
            preview = null,
            headers = null,
            chapterId = afterChapterId,
            index = -1,
            source = source,
        )
    }
}
