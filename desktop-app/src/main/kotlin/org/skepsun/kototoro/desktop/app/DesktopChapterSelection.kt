package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceChapter

/**
 * Android enters chapter multi-select by long-pressing a row; a desktop mouse gets the same state from a select toggle
 * or a mouse hold. Only chapter ids are kept, so a refreshed chapter list cannot carry a stale selection forward.
 */
internal data class DesktopChapterSelection(val ids: Set<Long> = emptySet()) {

    val isEmpty: Boolean get() = ids.isEmpty()

    val size: Int get() = ids.size

    fun contains(chapter: SourceChapter): Boolean = chapter.id in ids

    fun toggle(chapter: SourceChapter): DesktopChapterSelection =
        if (chapter.id in ids) copy(ids = ids - chapter.id) else copy(ids = ids + chapter.id)

    /** Everything currently visible, which is what Android's "select all" covers in a filtered list too. */
    fun withAll(visible: List<SourceChapter>): DesktopChapterSelection = copy(ids = visible.mapTo(LinkedHashSet()) { it.id })

    /** Reading order for the batch actions, so "mark as read" lands on the last chapter the user picked. */
    fun resolve(ordered: List<SourceChapter>): List<SourceChapter> = ordered.filter { it.id in ids }
}
