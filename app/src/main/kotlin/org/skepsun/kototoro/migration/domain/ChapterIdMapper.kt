package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.model.ContentChapter

/**
 * Maps chapters of the old entry to chapters of the new one. A chapter keeps its branch
 * when the new entry has it, otherwise it lands in the new entry's largest branch; inside
 * the branch the same volume and positive number wins, then the same position (clamped).
 */
object ChapterIdMapper {

    fun map(old: List<ContentChapter>, new: List<ContentChapter>): Map<Long, Long> {
        if (old.isEmpty() || new.isEmpty()) return emptyMap()
        val newByBranch = new.groupBy { it.branch }
        val fallback = largestBranch(newByBranch)
        val oldByBranch = old.groupBy { it.branch }
        val result = HashMap<Long, Long>(old.size)
        for ((branch, oldChapters) in oldByBranch) {
            val target = newByBranch[branch] ?: fallback
            oldChapters.forEachIndexed { index, chapter ->
                val byNumber = if (chapter.number > 0f) {
                    target.firstOrNull { it.volume == chapter.volume && it.number == chapter.number }
                } else {
                    null
                }
                result[chapter.id] = (byNumber ?: target.getOrNull(index) ?: target.last()).id
            }
        }
        return result
    }

    fun idAtIndex(new: List<ContentChapter>, index: Int): Long? {
        if (new.isEmpty()) return null
        val branch = largestBranch(new.groupBy { it.branch })
        return (branch.getOrNull(index) ?: branch.last()).id
    }

    private fun largestBranch(byBranch: Map<String?, List<ContentChapter>>): List<ContentChapter> =
        byBranch.values.maxBy { it.size }
}
