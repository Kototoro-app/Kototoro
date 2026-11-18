package org.skepsun.kototoro.search.domain

enum class SearchKind {

    SIMPLE, TITLE, AUTHOR, TAG, ADVANCED
}

/** The fields of an advanced search; blank means "not asked". */
data class AdvancedSearchParams(
    val query: String = "",
    val title: String = "",
    val tags: String = "",
    val author: String = "",
)

enum class SearchContentKind {
    MANGA,
    NOVEL,
    VIDEO,
}

val ALL_SEARCH_CONTENT_KINDS: Set<SearchContentKind> = SearchContentKind.entries.toSet()

/** The kind a source's content type name (`ContentType`/`SourceContent.contentType`) belongs to. */
fun searchContentKindOf(contentType: String?): SearchContentKind = when (contentType) {
    "NOVEL", "HENTAI_NOVEL" -> SearchContentKind.NOVEL
    "VIDEO", "HENTAI_VIDEO" -> SearchContentKind.VIDEO
    else -> SearchContentKind.MANGA
}
