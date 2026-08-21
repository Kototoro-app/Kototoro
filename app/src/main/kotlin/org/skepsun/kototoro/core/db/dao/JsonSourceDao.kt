package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType

@Dao
interface JsonSourceDao {

    /**
     * Observe enabled sources, ordered by name
     * Uses index on 'enabled' column for efficient filtering
     */
    @Query("SELECT * FROM json_sources WHERE enabled = 1 ORDER BY name")
    fun observeEnabled(): Flow<List<JsonSourceEntity>>

    @Query(
        """
		SELECT
			id,
			name,
			type,
			config,
			enabled,
			last_used_at AS lastUsedAt,
			is_pinned AS isPinned,
			1 AS hasExploreUrl,
			icon_url AS iconUrl
		FROM json_sources
		WHERE enabled = 1
		ORDER BY name
        """,
    )
    fun observeEnabledSummaries(): Flow<List<JsonSourceSummary>>

    @Query(
        """
		SELECT
			id,
			name,
			type,
			config,
			enabled,
			last_used_at AS lastUsedAt,
			is_pinned AS isPinned,
			1 AS hasExploreUrl,
			icon_url AS iconUrl
		FROM json_sources
		ORDER BY name
        """,
    )
    fun observeAllSummaries(): Flow<List<JsonSourceSummary>>

    /**
     * Observe all sources, ordered by name
     */
    @Query("SELECT * FROM json_sources ORDER BY name")
    fun observeAll(): Flow<List<JsonSourceEntity>>

    /**
     * Observe sources by type
     * Uses index on 'type' column for efficient filtering
     */
    @Query("SELECT * FROM json_sources WHERE type = :type ORDER BY name")
    fun observeByType(type: JsonSourceType): Flow<List<JsonSourceEntity>>

    /**
     * Observe enabled sources by type
     * Uses composite filtering with indexes on both 'enabled' and 'type'
     */
    @Query("SELECT * FROM json_sources WHERE enabled = 1 AND type = :type ORDER BY name")
    fun observeEnabledByType(type: JsonSourceType): Flow<List<JsonSourceEntity>>

    /**
     * Get recently used sources (for quick access)
     * Uses index on 'last_used_at' for efficient sorting
     */
    @Query("SELECT * FROM json_sources WHERE enabled = 1 AND last_used_at > 0 ORDER BY last_used_at DESC LIMIT :limit")
    fun observeRecentlyUsed(limit: Int = 10): Flow<List<JsonSourceEntity>>

    /**
     * Get source by ID
     * Primary key lookup is automatically indexed
     */
    @Query("SELECT * FROM json_sources WHERE id = :id")
    suspend fun getById(id: String): JsonSourceEntity?

    /**
     * Get multiple sources by IDs (batch query)
     * More efficient than multiple individual queries
     */
    @Query("SELECT * FROM json_sources WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<JsonSourceEntity>

    /**
     * Count sources by type
     * Uses index on 'type' column
     */
    @Query("SELECT COUNT(*) FROM json_sources WHERE type = :type")
    suspend fun countByType(type: JsonSourceType): Int

    /**
     * Count enabled sources
     * Uses index on 'enabled' column
     */
    @Query("SELECT COUNT(*) FROM json_sources WHERE enabled = 1")
    suspend fun countEnabled(): Int

    /**
     * Insert a single source
     * REPLACE strategy handles conflicts automatically
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: JsonSourceEntity)

    /**
     * Batch insert sources
     * More efficient than multiple individual inserts
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<JsonSourceEntity>)

    /**
     * Update a source entity
     */
    @Update
    suspend fun update(source: JsonSourceEntity)

    /**
     * Update enabled status
     * Indexed update on primary key
     */
    @Query("UPDATE json_sources SET enabled = :enabled, updated_at = :timestamp WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, timestamp: Long)

    /**
     * Batch update enabled status
     * More efficient than multiple individual updates
     */
    @Query("UPDATE json_sources SET enabled = :enabled, updated_at = :timestamp WHERE id IN (:ids)")
    suspend fun setEnabledBatch(ids: List<String>, enabled: Boolean, timestamp: Long)

    /**
     * Update pinned status
     */
    @Query("UPDATE json_sources SET is_pinned = :isPinned, updated_at = :timestamp WHERE id = :id")
    suspend fun setPinned(id: String, isPinned: Boolean, timestamp: Long)

    /**
     * Batch update pinned status
     */
    @Query("UPDATE json_sources SET is_pinned = :isPinned, updated_at = :timestamp WHERE id IN (:ids)")
    suspend fun setPinnedBatch(ids: List<String>, isPinned: Boolean, timestamp: Long)

    /**
     * Update last used timestamp
     * Indexed update on primary key
     */
    @Query("UPDATE json_sources SET last_used_at = :timestamp WHERE id = :id")
    suspend fun setLastUsed(id: String, timestamp: Long)

    @Query("UPDATE json_sources SET icon_url = :iconUrl, updated_at = :timestamp WHERE id = :id AND (icon_url IS NULL OR icon_url = '')")
    suspend fun fillMissingIconUrl(id: String, iconUrl: String, timestamp: Long)

    /**
     * Delete a source entity
     */
    @Delete
    suspend fun delete(source: JsonSourceEntity)

    /**
     * Delete source by ID
     * Indexed delete on primary key
     */
    @Query("DELETE FROM json_sources WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Batch delete sources by IDs
     * More efficient than multiple individual deletes
     */
    @Query("DELETE FROM json_sources WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Delete all sources of a specific type
     * Uses index on 'type' column
     */
    @Query("DELETE FROM json_sources WHERE type = :type")
    suspend fun deleteByType(type: JsonSourceType)
}
