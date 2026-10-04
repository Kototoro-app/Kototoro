package org.skepsun.kototoro.backups.external

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity

/** The active library rows an external-app export is built from. */
data class ExternalBackupLibrary(
    val favouriteEntries: List<FavouriteEntity>,
    val historyEntries: List<HistoryEntity>,
) {
    val candidateMangaIds: List<Long> = (
        favouriteEntries.map(FavouriteEntity::mangaId) +
            historyEntries.map(HistoryEntity::mangaId)
        )
        .distinct()

    val favouriteEntriesByMangaId: Map<Long, List<FavouriteEntity>> = favouriteEntries.groupBy(FavouriteEntity::mangaId)
    val historyByMangaId: Map<Long, HistoryEntity> = historyEntries.associateBy(HistoryEntity::mangaId)
}

suspend fun MangaDatabase.readExternalBackupLibrary(): ExternalBackupLibrary {
    return ExternalBackupLibrary(
        favouriteEntries = getFavouritesDao().findAllActiveEntries(),
        historyEntries = getHistoryDao().findAllEntriesIncludingDeleted().filter { it.deletedAt == 0L },
    )
}
