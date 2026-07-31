package org.skepsun.kototoro.video.ui

import org.skepsun.kototoro.parsers.model.ContentChapter

internal data class PlayerChapterGroup(
    val name: String?,
    val chapters: List<ContentChapter>,
)

internal fun groupPlayerChapters(chapters: List<ContentChapter>): List<PlayerChapterGroup> {
    return chapters
        .groupBy { chapter -> chapter.branch?.trim()?.takeIf(String::isNotEmpty) }
        .map { (name, groupedChapters) ->
            PlayerChapterGroup(name = name, chapters = groupedChapters)
        }
}

internal fun findPlayerChapterGroupIndex(
    groups: List<PlayerChapterGroup>,
    chapterId: Long?,
): Int {
    if (chapterId == null) return 0
    return groups.indexOfFirst { group -> group.chapters.any { it.id == chapterId } }
        .coerceAtLeast(0)
}
