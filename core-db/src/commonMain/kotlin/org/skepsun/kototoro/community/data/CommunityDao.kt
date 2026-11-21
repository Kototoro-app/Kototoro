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

    /** Events no relay accepted yet whose next try is due. */
    @Query("SELECT * FROM community_outbox WHERE published_at = 0 AND next_attempt_at <= :now ORDER BY created_at LIMIT :limit")
    suspend fun findDueOutbox(now: Long, limit: Int): List<CommunityOutboxEntity>

    /** Accepted events still waiting for a second relay or an index run, whose next check is due. */
    @Query("SELECT * FROM community_outbox WHERE published_at > 0 AND next_attempt_at <= :now ORDER BY created_at LIMIT :limit")
    suspend fun findDueUnconfirmed(now: Long, limit: Int): List<CommunityOutboxEntity>

    @Query("SELECT * FROM community_outbox WHERE event_id = :eventId")
    suspend fun findOutbox(eventId: String): CommunityOutboxEntity?

    /** Events no relay has accepted yet; accepted ones waiting for confirmation are already public. */
    @Query("SELECT COUNT(*) FROM community_outbox WHERE published_at = 0")
    fun observeOutboxCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM community_outbox WHERE published_at = 0")
    suspend fun countOutbox(): Int

    @Query("SELECT COUNT(*) FROM community_outbox WHERE published_at > 0")
    suspend fun countUnconfirmed(): Int

    /** Events of one kind no relay has accepted yet, e.g. the reader's own comments shown as pending. */
    @Query("SELECT * FROM community_outbox WHERE kind = :kind AND published_at = 0 ORDER BY created_at")
    suspend fun findOutboxByKind(kind: Int): List<CommunityOutboxEntity>

    @Query("DELETE FROM community_outbox WHERE event_id = :eventId")
    suspend fun deleteOutbox(eventId: String)

    @Query("SELECT * FROM community_resolutions WHERE manga_id = :mangaId")
    suspend fun findResolutions(mangaId: Long): List<CommunityResolutionEntity>

    /** Every tracker id search ever bound, for naming the whole library at once. */
    @Query("SELECT * FROM community_resolutions WHERE status = 'BOUND'")
    suspend fun findBoundResolutions(): List<CommunityResolutionEntity>

    @Query("SELECT * FROM community_ratings")
    suspend fun findAllRatings(): List<CommunityRatingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResolution(resolution: CommunityResolutionEntity)

    /** Everything the community feature stored; the reader turned it off or asked for it gone. */
    @Query("DELETE FROM community_ratings")
    suspend fun clearRatings()

    @Query("DELETE FROM community_outbox")
    suspend fun clearOutbox()
}
