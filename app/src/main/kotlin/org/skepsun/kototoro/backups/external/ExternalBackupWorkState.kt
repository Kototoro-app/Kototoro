package org.skepsun.kototoro.backups.external

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
    val favourites = getFavouritesDao().findAllActiveEntries()
    val history = getHistoryDao().findAllEntriesIncludingDeleted()
        .filter { it.deletedAt == 0L }
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
