package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.SourceBookmark
import org.skepsun.kototoro.core.library.SourceLibraryEntry
import org.skepsun.kototoro.bookmarks.domain.normalizeNovelBookmarkPreview
import org.skepsun.kototoro.reader.novel.novelHtmlToText

class IosLibraryEntry internal constructor(
    value: SourceLibraryEntry,
    metadata: org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity? = null,
) {
    val content = IosSourceContent(value.content, metadata)
    val categoryIds = value.categoryIds.map(Long::toInt)
    val updatedAt = value.updatedAt
    val progress = value.progressPercent ?: 0f
    val hasProgress = value.progressPercent != null
    /** History only: the chapter last read, named like the chapter list does. */
    val lastChapterTitle: String? = value.lastChapter?.let { it.title.takeIf(String::isNotBlank) ?: "第 ${chapterNumber(it.number)} 话" }
}

data class IosReadingPosition(val chapterId: String, val index: Int, val scroll: Float, val progress: Float)

class IosBookmark internal constructor(internal val value: SourceBookmark) {
    val id: String get() = value.pageId.toString()
    val chapterId: String get() = value.chapterId.toString()
    val index: Int get() = value.page
    val scroll: Int get() = value.scroll
    val preview: String get() = value.preview
    val createdAt: Long get() = value.createdAt
    val percent: Float get() = value.percent
    val textPreview: String = normalizeNovelBookmarkPreview(value.preview) { novelHtmlToText(it, keepImages = false) }
}

data class IosUpdate(val content: IosSourceContent, val newChapters: Int, val checkedAt: Long)
data class IosUpdateReport(val checked: Int, val withUpdates: Int, val failed: Int, val newChapters: Int)
