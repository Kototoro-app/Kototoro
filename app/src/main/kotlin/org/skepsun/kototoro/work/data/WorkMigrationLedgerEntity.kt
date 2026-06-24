package org.skepsun.kototoro.work.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_WORK_MIGRATION_LEDGER

@Entity(
	tableName = TABLE_WORK_MIGRATION_LEDGER,
	primaryKeys = ["legacy_table", "legacy_key", "migration_version"],
	indices = [
		Index(name = "idx_work_migration_ledger_target_status", value = ["target_entity_id", "status"]),
		Index(name = "idx_work_migration_ledger_status", value = ["status"]),
	],
)
data class WorkMigrationLedgerEntity(
	@ColumnInfo(name = "legacy_table") val legacyTable: String,
	@ColumnInfo(name = "legacy_key") val legacyKey: String,
	@ColumnInfo(name = "legacy_checksum") val legacyChecksum: String?,
	@ColumnInfo(name = "target_entity_id") val targetEntityId: Long?,
	@ColumnInfo(name = "migration_version") val migrationVersion: Int,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "migrated_at") val migratedAt: Long,
)
