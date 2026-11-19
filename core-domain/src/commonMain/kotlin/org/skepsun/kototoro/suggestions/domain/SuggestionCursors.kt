package org.skepsun.kototoro.suggestions.domain

/**
 * Where incremental loading continues per source: the next list page to ask for, counted from the first one. Pages 0 and 1
 * are what a full refresh reads, so a source with no stored cursor continues from [START].
 */
object SuggestionCursors {
    const val START = 2

    /** Pages consumed by one incremental run: the page asked for and the one after it. */
    const val STEP = 2

    fun parse(raw: Collection<String>): Map<String, Int> = raw.mapNotNull { entry ->
        val at = entry.lastIndexOf('=')
        val page = if (at > 0) entry.substring(at + 1).toIntOrNull() else null
        page?.let { entry.substring(0, at) to it }
    }.toMap()

    fun encode(cursors: Map<String, Int>): Set<String> = cursors.mapTo(LinkedHashSet()) { (name, page) -> "$name=$page" }

    /** The cursors after a run in which [answered] sources replied; the others stay where they were. */
    fun advance(cursors: Map<String, Int>, answered: Collection<String>): Map<String, Int> =
        cursors + answered.associateWith { (cursors[it] ?: START) + STEP }
}
