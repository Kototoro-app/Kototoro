package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.parsers.model.ContentType

data class DuplicateGroupEntry(val row: LibraryRow, val sourceBroken: Boolean)

data class LibraryDuplicateGroup(
    /** Stable identity of the group (sorted entry ids), used to remember "not duplicates". */
    val key: String,
    val entries: List<DuplicateGroupEntry>,
    val recommendedId: Long,
)

/**
 * Finds favourites that are the same work. Entries of one content family are linked when
 * any normalized title or alt title matches, transitively (union-find), so "A" ~ "B" via an
 * alt title and "B" ~ "C" via traditional/simplified folding form a single group.
 */
object DuplicateGrouper {

    fun group(
        rows: List<LibraryRow>,
        unhealthySources: Set<String>,
        ignoredKeys: Set<String>,
    ): List<LibraryDuplicateGroup> {
        val parent = IntArray(rows.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        val owner = HashMap<String, Int>()
        rows.forEachIndexed { index, row ->
            val family = row.family() ?: return@forEachIndexed
            for (key in DuplicateMatcher.keys(row.title, row.altTitleList)) {
                val scoped = "${family.name}|$key"
                val other = owner.putIfAbsent(scoped, index) ?: continue
                parent[find(index)] = find(other)
            }
        }

        return rows.indices.groupBy(::find).values
            .filter { it.size > 1 }
            .map { indices ->
                val entries = indices.map { DuplicateGroupEntry(rows[it], rows[it].source in unhealthySources) }
                LibraryDuplicateGroup(
                    key = entries.map { it.row.id }.sorted().joinToString(","),
                    entries = entries.sortedWith(recommendation),
                    recommendedId = entries.minWith(recommendation).row.id,
                )
            }
            .filterNot { it.key in ignoredKeys }
            .sortedWith(compareByDescending<LibraryDuplicateGroup> { g -> g.entries.any { it.sourceBroken } }
                .thenBy { it.entries.first().row.title })
    }

    /** True when [a] was read further than [b]. */
    fun isFurther(a: LibraryRow, b: LibraryRow): Boolean = progressOrder.compare(a, b) > 0

    // A known chapter number beats a bare percentage; percentages only compare with each other.
    private val progressOrder = compareBy<LibraryRow> { it.historyChapterNumber ?: -1f }
        .thenBy { it.historyPercent ?: -1f }

    // Best first: working source, then furthest read, then most chapters.
    private val recommendation = compareBy<DuplicateGroupEntry> { it.sourceBroken }
        .thenDescending(compareBy(progressOrder) { it.row })
        .thenByDescending { it.row.knownChapters }
        .thenBy { it.row.id }

    private fun LibraryRow.family(): ContentTypeFamily? =
        contentType?.let { name -> ContentType.entries.firstOrNull { it.name == name } }?.contentFamily()
}
