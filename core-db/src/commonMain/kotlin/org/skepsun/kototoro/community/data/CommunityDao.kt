package org.skepsun.kototoro.community.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDao {

    @Query("SELECT * FROM community_ratings WHERE manga_id = :mangaId")
    fun observeRating(mangaId: Long): Flow<CommunityRatingEntity?>

    @Query("SELECT * FROM community_ratings WHERE manga_id = :mangaId")
    suspend fun findRating(mangaId: Long): CommunityRatingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRating(rating: CommunityRatingEntity)

    @Query("DELETE FROM community_ratings WHERE manga_id = :mangaId")
    suspend fun deleteRating(mangaId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(event: CommunityOutboxEntity)

    @Query("SELECT * FROM community_outbox WHERE next_attempt_at <= :now ORDER BY created_at LIMIT :limit")
    suspend fun findDueOutbox(now: Long, limit: Int): List<CommunityOutboxEntity>

    @Query("SELECT COUNT(*) FROM community_outbox")
    fun observeOutboxCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM community_outbox")
    suspend fun countOutbox(): Int

    @Query("DELETE FROM community_outbox WHERE event_id = :eventId")
    suspend fun deleteOutbox(eventId: String)

    @Query("SELECT * FROM community_resolutions WHERE manga_id = :mangaId")
    suspend fun findResolutions(mangaId: Long): List<CommunityResolutionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResolution(resolution: CommunityResolutionEntity)

    /** Everything the community feature stored; the reader turned it off or asked for it gone. */
    @Query("DELETE FROM community_ratings")
    suspend fun clearRatings()

    @Query("DELETE FROM community_outbox")
    suspend fun clearOutbox()
}
