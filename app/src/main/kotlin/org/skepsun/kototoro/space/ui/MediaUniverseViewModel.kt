package org.skepsun.kototoro.space.ui

import org.skepsun.kototoro.parsers.model.Content

data class MediaUniverseItem(
    val content: Content,
    val inHistory: Boolean,
    val inFavorites: Boolean,
)

data class MediaUniverseUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val items: List<MediaUniverseItem> = emptyList(),
)

/**
 * Projection-first merge of the history and favourites feeds: the projection
 * (content) id is now the work identity, so equal ids coalesce into one row that
 * records both memberships.
 */
internal fun mergeMediaUniverseItems(
    history: List<Content>,
    favorites: List<Content>,
): List<MediaUniverseItem> {
    val merged = LinkedHashMap<Long, MediaUniverseItem>()
    fun add(content: Content, inHistory: Boolean, inFavorites: Boolean) {
        val existing = merged[content.id]
        merged[content.id] = MediaUniverseItem(
            content = existing?.content ?: content,
            inHistory = existing?.inHistory == true || inHistory,
            inFavorites = existing?.inFavorites == true || inFavorites,
        )
    }
    history.forEach { add(it, inHistory = true, inFavorites = false) }
    favorites.forEach { add(it, inHistory = false, inFavorites = true) }
    return merged.values.toList()
}
