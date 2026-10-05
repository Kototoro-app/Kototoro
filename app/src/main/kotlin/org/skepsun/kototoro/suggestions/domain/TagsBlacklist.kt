package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.almostEquals

/** Android's model-typed view of the shared [SuggestionTagBlacklist]. */
class TagsBlacklist(
    tags: Set<String>,
    threshold: Float,
) {
    private val shared = SuggestionTagBlacklist(tags) { a, b -> a.almostEquals(b, threshold) }

    fun isNotEmpty() = shared.isNotEmpty()

    operator fun contains(manga: Content): Boolean = shared.containsAny(manga.tags.map { it.title })

    operator fun contains(tag: ContentTag): Boolean = shared.containsTitle(tag.title)
}
