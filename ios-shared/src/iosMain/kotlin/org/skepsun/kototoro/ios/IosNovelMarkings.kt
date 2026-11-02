package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.NovelMarkingStore
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity

/** Android's novel highlights on iOS, in the shared `novel_markings` table (see `NovelMarkingStore`). */
class IosNovelMarkings internal constructor(private val store: NovelMarkingStore) {

    suspend fun forWork(content: IosSourceContent): List<IosNovelMarking> =
        store.forWork(content.value.id).map(::IosNovelMarking)

    suspend fun forChapter(content: IosSourceContent, chapter: IosSourceChapter): List<IosNovelMarking> =
        forWork(content).filter { it.chapterId == chapter.id }

    /** Highlights `start until end` of the chapter's converted text, or removes the identical highlight. */
    suspend fun toggle(content: IosSourceContent, chapter: IosSourceChapter, start: Int, end: Int, text: String,
        color: Int, style: Int): Boolean =
        store.toggle(content.value.id, chapter.value.id,
            content.value.chapters.orEmpty().indexOfFirst { it.id == chapter.value.id }.coerceAtLeast(0), start, end, text,
            color, style)

    /** Android's color and line style of a highlight (`NovelMarkingColor` / `NovelMarkingStyle` ids). */
    suspend fun updateStyle(id: Long, color: Int, style: Int) = store.updateStyle(id, color, style)

    suspend fun updateNote(id: Long, note: String?) = store.updateNote(id, note)

    suspend fun delete(id: Long) = store.delete(id)
}

class IosNovelMarking internal constructor(value: NovelMarkingEntity) {
    val id: Long = value.id
    val chapterId: String = value.chapterId.toString()
    val chapterIndex: Int = value.chapterIndex
    val start: Int = value.startOffset
    val end: Int = value.endOffset
    val text: String = value.selectedText
    val note: String? = value.note
    val updatedAt: Long = value.updatedAt
    /** Android's `NovelMarkingColor` id: yellow, green, blue, pink, orange. */
    val color: Int = value.color
    /** Android's `NovelMarkingStyle` id: underline, wavy line, marker. */
    val style: Int = value.style
}

/** A highlight placed in a chapter or on a page: `start until end` in that text. */
data class IosTextRange(val id: Long, val start: Int, val end: Int)
