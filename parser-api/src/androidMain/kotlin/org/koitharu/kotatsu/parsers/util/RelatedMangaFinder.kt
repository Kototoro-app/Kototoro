package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.*
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

public class RelatedMangaFinder(
    private val parsers: Collection<MangaParser>,
) {

    public suspend operator fun invoke(seed: Manga): List<Manga> = withContext(Dispatchers.Default) {
        coroutineScope {
            parsers.singleOrNull()?.let { parser ->
                findRelatedImpl(this, parser, seed)
            } ?: parsers.map { parser ->
                async {
                    findRelatedImpl(this, parser, seed)
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun findRelatedImpl(scope: CoroutineScope, parser: MangaParser, seed: Manga): List<Manga> {
        val words = HashSet<String>()
        words += seed.title.splitByWhitespace()
        seed.altTitles.forEach {
            words += it.splitByWhitespace()
        }
        if (words.isEmpty()) {
            return emptyList()
        }
        val results = words.map { keyword ->
            scope.async {
                val result = parser.getList(
                    0,
                    if (SortOrder.RELEVANCE in parser.availableSortOrders) {
                        SortOrder.RELEVANCE
                    } else {
                        parser.availableSortOrders.first()
                    },
                    MangaListFilter(
                        query = keyword,
                    ),
                )
                result.filter { it.id != seed.id && it.containKeyword(keyword) }
            }
        }.awaitAll()
        return results.minBy { if (it.isEmpty()) Int.MAX_VALUE else it.size }
    }

    private fun Manga.containKeyword(keyword: String): Boolean {
        return title.contains(keyword, ignoreCase = true)
            || altTitles.any { it.contains(keyword, ignoreCase = true) }
    }
}
