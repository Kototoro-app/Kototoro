package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 持久化恢复会话 checkpoint：使备份恢复可以跨进程断点续传。
 *
 * 每个恢复会话一行（[RestoreCheckpointEntity.id] = 服务派生的稳定 restore_id）。
 * - [doneJson] 记录已成功写入数据库的节集合；
 * - [mappingJson] 记录恢复所需的跨节映射快照（entityIdMapping / legacyCategoryIdMapping /
 *   remoteLocalBindings），与 done 在同一 UPDATE 中落盘，保证「标记 done 的映射相关节必有快照」。
 * 恢复成功后由调用方删除；失败/被杀死时保留以支持重试续传。
 *
 * 表故意不挂任何外键：恢复的 SNAPSHOT_REPLACE 清库路径不得波及 checkpoint 本身。
 */
@Entity(tableName = "restore_checkpoint")
class RestoreCheckpointEntity(
	@PrimaryKey
	@ColumnInfo(name = "id") val id: String,
	@ColumnInfo(name = "mode") val mode: String,
	@ColumnInfo(name = "sections_json") val sectionsJson: String,
	@ColumnInfo(name = "done_json") val doneJson: String,
	@ColumnInfo(name = "mapping_json") val mappingJson: String?,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface RestoreCheckpointDao {

	@Query("SELECT * FROM restore_checkpoint WHERE id = :id")
	suspend fun findById(id: String): RestoreCheckpointEntity?

	@Upsert
	suspend fun upsert(entity: RestoreCheckpointEntity)

	@Query("DELETE FROM restore_checkpoint WHERE id = :id")
	suspend fun deleteById(id: String)

	@Query("DELETE FROM restore_checkpoint")
	suspend fun clearAll()
}
