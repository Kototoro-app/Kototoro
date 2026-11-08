package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
abstract class TrackScheduleDao {

    @Query("SELECT * FROM track_schedule WHERE manga_id = :mangaId LIMIT 1")
    abstract suspend fun find(mangaId: Long): TrackScheduleEntity?

    @Query("SELECT * FROM track_schedule WHERE manga_id IN (:mangaIds)")
    abstract suspend fun findByIds(mangaIds: Collection<Long>): List<TrackScheduleEntity>

    /** Works that failed at least [minFailures] checks in a row, most failures first. */
    @Query("SELECT * FROM track_schedule WHERE fail_count >= :minFailures ORDER BY fail_count DESC")
    abstract suspend fun findFailing(minFailures: Int): List<TrackScheduleEntity>

    @Upsert
    abstract suspend fun upsert(entity: TrackScheduleEntity)

    /** Schedules of works that are no longer tracked. */
    @Query("DELETE FROM track_schedule WHERE manga_id NOT IN (SELECT manga_id FROM tracks)")
    abstract suspend fun gc()
}
