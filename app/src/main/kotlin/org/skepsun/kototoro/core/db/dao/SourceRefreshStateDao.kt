package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.SourceRefreshStateEntity

/**
 * DAO over the per-content refresh bookkeeping of strict source origins.
 *
 * Consumed by the source-recovery layer to track whether a content item has ever refreshed
 * successfully against its origin and when the last attempt happened.
 */
@Dao
abstract class SourceRefreshStateDao {

    @Query(
        "SELECT * FROM source_refresh_state WHERE source_key = :sourceKey AND content_id = :contentId",
    )
    abstract suspend fun get(sourceKey: String, contentId: Long): SourceRefreshStateEntity?

    @Query("SELECT * FROM source_refresh_state WHERE source_key = :sourceKey")
    abstract suspend fun findBySource(sourceKey: String): List<SourceRefreshStateEntity>

    @Query("SELECT * FROM source_refresh_state WHERE source_key = :sourceKey")
    abstract fun observeBySource(sourceKey: String): Flow<List<SourceRefreshStateEntity>>

    @Upsert
    abstract suspend fun upsert(state: SourceRefreshStateEntity)

    @Query("DELETE FROM source_refresh_state WHERE source_key = :sourceKey AND content_id = :contentId")
    abstract suspend fun delete(sourceKey: String, contentId: Long)

    @Query("DELETE FROM source_refresh_state WHERE source_key = :sourceKey")
    abstract suspend fun deleteBySource(sourceKey: String)
}
