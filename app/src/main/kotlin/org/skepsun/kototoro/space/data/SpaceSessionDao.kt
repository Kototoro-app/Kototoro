package org.skepsun.kototoro.space.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class SpaceSessionDao {

	@Query("SELECT * FROM space_session WHERE space_id = :spaceId LIMIT 1")
	abstract suspend fun findSession(spaceId: String): SpaceSessionEntity?

	@Query(
		"""
		SELECT * FROM space_navigation_entry
		WHERE space_id = :spaceId
		ORDER BY stack_key, position
		""",
	)
	abstract suspend fun findNavigationEntries(spaceId: String): List<SpaceNavigationEntryEntity>

	@Upsert
	protected abstract suspend fun upsertSession(entity: SpaceSessionEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	protected abstract suspend fun insertNavigationEntries(entries: List<SpaceNavigationEntryEntity>)

	@Query("DELETE FROM space_navigation_entry WHERE space_id = :spaceId")
	protected abstract suspend fun deleteNavigationEntries(spaceId: String)

	@Query("DELETE FROM space_session WHERE space_id = :spaceId")
	protected abstract suspend fun deleteSession(spaceId: String)

	@Transaction
	open suspend fun replaceSnapshot(
		session: SpaceSessionEntity,
		entries: List<SpaceNavigationEntryEntity>,
	) {
		require(entries.all { it.spaceId == session.spaceId }) {
			"Navigation entries must belong to the session Space"
		}
		upsertSession(session)
		deleteNavigationEntries(session.spaceId)
		if (entries.isNotEmpty()) {
			insertNavigationEntries(entries)
		}
	}

	@Transaction
	open suspend fun deleteSnapshot(spaceId: String) {
		deleteNavigationEntries(spaceId)
		deleteSession(spaceId)
	}
}
