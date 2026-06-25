package org.skepsun.kototoro.backups.external

import kotlinx.coroutines.flow.toList
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteCategoryMembership
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity

internal data class ExternalBackupWorkState(
    val favouriteEntries: List<FavouriteEntity>,
    val historyEntries: List<HistoryEntity>,
    val categoryMemberships: List<FavouriteCategoryMembership>,
) {
    val candidateMangaIds: List<Long> = (
        favouriteEntries.map(FavouriteEntity::mangaId) +
            historyEntries.map(HistoryEntity::mangaId)
        )
        .distinct()

    val favouriteEntriesByMangaId: Map<Long, List<FavouriteEntity>> = favouriteEntries.groupBy(FavouriteEntity::mangaId)
    val historyByMangaId: Map<Long, HistoryEntity> = historyEntries.associateBy(HistoryEntity::mangaId)
    val categoryMembershipsByMangaId: Map<Long, List<FavouriteCategoryMembership>> =
        categoryMemberships.groupBy(FavouriteCategoryMembership::mangaId)
}

internal suspend fun MangaDatabase.readExternalBackupWorkState(): ExternalBackupWorkState {
    val favourites = getWorkFavouritesDao().dump().toList()
        .mapNotNull { favourite ->
            val mangaId = favourite.anchorMangaId ?: return@mapNotNull null
            if (favourite.deletedAt != 0L) {
                return@mapNotNull null
            }
            FavouriteEntity(
                mangaId = mangaId,
                categoryId = favourite.categoryId,
                sortKey = favourite.sortKey,
                isPinned = favourite.isPinned,
                createdAt = favourite.createdAt,
                deletedAt = favourite.deletedAt,
                updatedAt = favourite.updatedAt,
            )
        }
    val history = getWorkHistoryDao().dump().toList()
        .filter { it.deletedAt == 0L }
        .map { entry ->
            HistoryEntity(
                mangaId = entry.anchorMangaId,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                chapterId = entry.chapterId,
                page = entry.page,
                scroll = entry.scroll,
                percent = entry.percent,
                deletedAt = entry.deletedAt,
                chaptersCount = entry.chaptersCount,
                parentChapterId = entry.parentChapterId,
            )
        }
    val memberships = favourites.map { favourite ->
        FavouriteCategoryMembership(
            mangaId = favourite.mangaId,
            categoryId = favourite.categoryId,
        )
    }
    return ExternalBackupWorkState(
        favouriteEntries = favourites,
        historyEntries = history,
        categoryMemberships = memberships,
    )
}
