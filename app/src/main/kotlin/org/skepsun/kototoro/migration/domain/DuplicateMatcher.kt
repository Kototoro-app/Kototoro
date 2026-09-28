package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.model.contentFamily
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.parsers.model.ContentType

/** Exact (normalized) title or alt-title matching; no fuzzy matching on purpose. */
object DuplicateMatcher {

    fun keys(title: String, altTitles: Collection<String>): Set<String> =
        (listOf(title) + altTitles).map(TitleNormalizer::normalize).filterTo(mutableSetOf()) { it.isNotEmpty() }

    fun find(
        id: Long,
        title: String,
        altTitles: Collection<String>,
        family: ContentType?,
        library: List<LibraryRow>,
    ): List<LibraryRow> {
        val targetKeys = keys(title, altTitles)
        if (targetKeys.isEmpty()) return emptyList()
        val targetFamily = family?.contentFamily()
        return library.filter { row ->
            row.id != id &&
                row.contentFamily() == targetFamily &&
                keys(row.title, row.altTitleList).any { it in targetKeys }
        }
    }

    private fun LibraryRow.contentFamily() =
        contentType?.let { name -> ContentType.entries.firstOrNull { it.name == name } }?.contentFamily()
}
