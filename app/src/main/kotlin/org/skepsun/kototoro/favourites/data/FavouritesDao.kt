package org.skepsun.kototoro.favourites.data

import androidx.room.Dao
import androidx.room.Query

@Dao
abstract class FavouritesDao {

	@Query("SELECT * FROM favourites ORDER BY created_at DESC")
	abstract suspend fun findAllEntriesIncludingDeleted(): List<FavouriteEntity>

	@Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC")
	abstract suspend fun findAllActiveEntries(): List<FavouriteEntity>

	@Query("DELETE FROM favourites")
	abstract suspend fun clear()

	@Query("DELETE FROM favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)
}
