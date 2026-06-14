package org.skepsun.kototoro.sync.domain

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncGcCoordinator @Inject constructor(
	private val dbProvider: javax.inject.Provider<org.skepsun.kototoro.core.db.MangaDatabase>,
) {

	private val defaultGcPeriod = TimeUnit.DAYS.toMillis(2)

	suspend fun gcIfNeeded(favourites: Boolean, history: Boolean, gcFavourites: Boolean, gcHistory: Boolean) {
		if (!gcFavourites && !gcHistory) return
		val db = dbProvider.get()
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		if (gcHistory || history) {
			db.getHistoryDao().gc(deletedAt)
			db.getWorkHistoryDao().gc(deletedAt)
		}
		if (gcFavourites || favourites) {
			db.getFavouritesDao().gc(deletedAt)
			db.getWorkFavouritesDao().gc(deletedAt)
			db.getFavouriteCategoriesDao().gc(deletedAt)
		}
	}
}
