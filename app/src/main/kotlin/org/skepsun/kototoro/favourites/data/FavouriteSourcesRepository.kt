package org.skepsun.kototoro.favourites.data

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

@Reusable
class FavouriteSourcesRepository @Inject constructor(
    private val db: MangaDatabase,
    private val sourcesRepository: ContentSourcesRepository,
) {
    suspend fun getDistinctFavouriteSourceNames(): List<String> {
        return db.getFavouritesDao().findDistinctSources()
    }

    suspend fun getFavouriteSources(): List<ContentSource> {
        val sourceNames = getDistinctFavouriteSourceNames().toSet()
        val allSources = sourcesRepository.getAllAvailableSourcesForListing()
        return allSources.filter { it.name in sourceNames }
    }

    suspend fun getFavouriteContentsBySource(sourceName: String): List<FavouriteContent> {
        return db.getFavouritesDao().findAllBySource(sourceName)
    }

    suspend fun getAllFavouriteContents(): List<FavouriteContent> {
        return db.getFavouritesDao().findAll()
    }

    suspend fun getFavouriteContentsByIds(mangaIds: Set<Long>): List<FavouriteContent> {
        if (mangaIds.isEmpty()) {
            return emptyList()
        }
        return db.getFavouritesDao().findAllByIds(mangaIds.toLongArray())
    }

    suspend fun getCategoriesForManga(mangaId: Long): List<Long> {
        return db.getFavouritesDao().findCategoriesIds(mangaId)
    }
}
