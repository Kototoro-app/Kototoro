package org.skepsun.kototoro.search.domain

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

enum class SearchContentKind {
    MANGA,
    NOVEL,
    VIDEO,
}

val ALL_SEARCH_CONTENT_KINDS: Set<SearchContentKind> = setOf(SearchContentKind.MANGA, SearchContentKind.NOVEL)

data class SearchContentKindOption(
    val kind: SearchContentKind,
    @StringRes val titleRes: Int,
)

val SEARCH_CONTENT_KIND_OPTIONS: List<SearchContentKindOption> = listOf(
    SearchContentKindOption(SearchContentKind.MANGA, R.string.content_type_manga),
    SearchContentKindOption(SearchContentKind.NOVEL, R.string.content_type_novel),
)

fun SearchContentKind.matches(source: ContentSource): Boolean {
    val contentType = source.getContentType()
    return when (this) {
        SearchContentKind.MANGA -> contentType !in setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL, ContentType.VIDEO, ContentType.HENTAI_VIDEO)
        SearchContentKind.NOVEL -> contentType in setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
        SearchContentKind.VIDEO -> contentType in setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
    }
}

fun SearchContentKind.matches(manga: Content): Boolean = matches(manga.source)

fun searchContentKindsFromNames(names: Collection<String>?): Set<SearchContentKind>? {
    if (names.isNullOrEmpty()) return null
    val kinds = names.mapNotNull { name ->
        runCatching { SearchContentKind.valueOf(name) }.getOrNull()?.takeIf { it in ALL_SEARCH_CONTENT_KINDS }
    }.toSet()
    return kinds.ifEmpty { null }
}

fun searchContentKindsToNames(kinds: Set<SearchContentKind>): ArrayList<String> {
    return ArrayList(kinds.map { it.name })
}
