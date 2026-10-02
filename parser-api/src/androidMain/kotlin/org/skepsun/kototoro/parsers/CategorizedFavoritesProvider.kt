package org.skepsun.kototoro.parsers

import org.skepsun.kototoro.parsers.model.Content

public interface CategorizedFavoritesProvider : FavoritesProvider {

    public suspend fun fetchFavoriteFolders(): List<ContentFavoriteFolder>

    public suspend fun fetchFavorites(folderId: String): List<Content>

    override suspend fun fetchFavorites(): List<Content> {
        return fetchFavoriteFolders().flatMap { fetchFavorites(it.id) }.distinctBy { it.url }
    }
}

public data class ContentFavoriteFolder(
    val id: String,
    val title: String,
    val count: Int? = null,
)
