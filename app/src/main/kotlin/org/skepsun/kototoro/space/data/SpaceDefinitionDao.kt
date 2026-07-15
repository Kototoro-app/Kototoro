package org.skepsun.kototoro.space.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDefinitionDao {

    @Query("SELECT * FROM space_definition WHERE deleted_at = 0 ORDER BY sort_key, created_at")
    fun observeAll(): Flow<List<SpaceDefinitionEntity>>

    @Query("SELECT * FROM space_definition WHERE space_id = :spaceId AND deleted_at = 0")
    suspend fun find(spaceId: String): SpaceDefinitionEntity?

    @Query("SELECT COUNT(*) FROM space_definition WHERE deleted_at = 0")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SpaceDefinitionEntity)

    @Update
    suspend fun update(entity: SpaceDefinitionEntity)

    @Query("UPDATE space_definition SET deleted_at = :deletedAt, enabled = 0, updated_at = :deletedAt WHERE space_id = :spaceId")
    suspend fun markDeleted(spaceId: String, deletedAt: Long)
}
