package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

/**
 * DAO over the [SourceOriginEntity] registry of strict source keys.
 *
 * Consumed by the source-recovery layer to look up and maintain the minimal stable metadata of
 * every known source origin (including offline Tsundoku repositories).
 */
@Dao
abstract class SourceOriginsDao {

    @Query("SELECT * FROM source_origins WHERE source_key = :sourceKey")
    abstract suspend fun getByKey(sourceKey: String): SourceOriginEntity?

    @Query("SELECT * FROM source_origins")
    abstract suspend fun findAll(): List<SourceOriginEntity>

    @Query("SELECT * FROM source_origins")
    abstract fun observeAll(): Flow<List<SourceOriginEntity>>

    @Upsert
    abstract suspend fun upsert(origin: SourceOriginEntity)

    @Query("DELETE FROM source_origins WHERE source_key = :sourceKey")
    abstract suspend fun deleteByKey(sourceKey: String)

    @Query("SELECT COUNT(*) FROM source_origins WHERE source_key = :sourceKey")
    abstract suspend fun countByKey(sourceKey: String): Int

    @Query("DELETE FROM source_origins")
    abstract suspend fun deleteAll()
}
