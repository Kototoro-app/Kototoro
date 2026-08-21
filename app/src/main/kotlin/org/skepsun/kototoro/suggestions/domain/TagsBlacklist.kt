package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.almostEquals

class TagsBlacklist(
    private val tags: Set<String>,
    private val threshold: Float,
) {

    fun isNotEmpty() = tags.isNotEmpty()

    operator fun contains(manga: Content): Boolean {
        if (tags.isEmpty()) {
            return false
        }
        for (mangaTag in manga.tags) {
            for (tagTitle in tags) {
                if (mangaTag.title.almostEquals(tagTitle, threshold)) {
                    return true
                }
            }
        }
        return false
    }

    operator fun contains(tag: ContentTag): Boolean = tags.any {
        it.almostEquals(tag.title, threshold)
    }
}
