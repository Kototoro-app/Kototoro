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
    val lastChapterTitle: String? = value.lastChapter?.let { it.title.takeIf(String::isNotBlank) ?: localized("第 %@ 话", chapterNumber(it.number)) }
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

/** New logs match stable identities; old logs can only resolve unique stored names. */
internal fun resolveUpdateEventChapters(content: IosSourceContent, names: List<String>, keys: List<String>): List<IosSourceChapter> {
    if (keys.isEmpty()) return resolveUpdateDownloadChapters(content, names)
    val byKey = content.chapters.groupBy { chapter ->
        chapter.value.let { org.skepsun.kototoro.tracker.domain.updateChapterKey(it.source.name, it.branch, it.url, it.id) }
    }
    if (keys.any { byKey[it]?.size != 1 }) return emptyList()
    return keys.distinct().map { byKey.getValue(it).single() }
}

internal fun resolveUpdateDownloadChapters(content: IosSourceContent, names: List<String>): List<IosSourceChapter> {
    val chapters = content.value.chapters.orEmpty().groupBy { it.title ?: "第 ${it.number} 章" }
    return names.distinct().map { name ->
        IosSourceChapter(chapters[name]?.singleOrNull() ?: return emptyList())
    }
}
data class IosUpdateReport(val checked: Int, val withUpdates: Int, val failed: Int, val newChapters: Int)
