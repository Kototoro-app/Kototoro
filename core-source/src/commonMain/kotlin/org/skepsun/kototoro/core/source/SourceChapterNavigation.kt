package org.skepsun.kototoro.core.source

/** Preserve the runtime's chapter order and stay within the current source/branch. */
object SourceChapterNavigation {
    fun adjacent(chapters: List<SourceChapter>, currentId: Long, forward: Boolean): SourceChapter? {
        val current = chapters.firstOrNull { it.id == currentId } ?: return null
        val branch = chapters.filter { it.branch == current.branch && it.source.name == current.source.name }
        val index = branch.indexOfFirst { it.id == currentId }
        return branch.getOrNull(index + if (forward) 1 else -1)
    }
}
