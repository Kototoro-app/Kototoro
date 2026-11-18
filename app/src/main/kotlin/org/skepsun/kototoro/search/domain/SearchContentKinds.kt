package org.skepsun.kototoro.search.domain

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

data class SearchContentKindOption(
    val kind: SearchContentKind,
    @StringRes val titleRes: Int,
)

val SEARCH_CONTENT_KIND_OPTIONS: List<SearchContentKindOption> = listOf(
    SearchContentKindOption(SearchContentKind.MANGA, R.string.content_type_manga),
    SearchContentKindOption(SearchContentKind.NOVEL, R.string.content_type_novel),
    SearchContentKindOption(SearchContentKind.VIDEO, R.string.content_type_video),
)

fun SearchContentKind.matches(source: ContentSource): Boolean =
    searchContentKindOf(source.getContentType().name) == this

fun SearchContentKind.matches(manga: Content): Boolean = matches(manga.source)

fun searchContentKindsFromNames(names: Collection<String>?): Set<SearchContentKind>? {
    if (names.isNullOrEmpty()) return null
    val kinds = names.mapNotNull { name ->
        runCatching { SearchContentKind.valueOf(name) }.getOrNull()
    }.toSet()
    return kinds.ifEmpty { null }
}

fun searchContentKindsToNames(kinds: Set<SearchContentKind>): ArrayList<String> {
    return ArrayList(kinds.map { it.name })
}
