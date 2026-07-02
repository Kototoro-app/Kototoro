package org.skepsun.kototoro.work.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WorkMigrationLedgerDao {

	@Query(
		"""
		SELECT * FROM work_migration_ledger
		WHERE legacy_table = :legacyTable
			AND legacy_key = :legacyKey
			AND migration_version = :migrationVersion
		LIMIT 1
		""",
	)
	suspend fun find(
		legacyTable: String,
		legacyKey: String,
		migrationVersion: Int,
	): WorkMigrationLedgerEntity?

	@Query(
		"""
		SELECT * FROM work_migration_ledger
		WHERE target_entity_id = :entityId
		ORDER BY migrated_at DESC
		""",
	)
	suspend fun findByTargetEntityId(entityId: Long): List<WorkMigrationLedgerEntity>

	@Query(
		"""
		SELECT * FROM work_migration_ledger
		WHERE legacy_table = :legacyTable
			AND legacy_key = :legacyKey
		ORDER BY migrated_at DESC
		LIMIT 1
		""",
	)
	suspend fun findLatest(
		legacyTable: String,
		legacyKey: String,
	): WorkMigrationLedgerEntity?

	@Query(
		"""
		SELECT EXISTS(
			SELECT 1 FROM work_migration_ledger
			WHERE legacy_table = :legacyTable
				AND legacy_key = :legacyKey
				AND migration_version = :migrationVersion
				AND status = :status
		)
		""",
	)
	suspend fun existsWithStatus(
		legacyTable: String,
		legacyKey: String,
		migrationVersion: Int,
		status: String,
	): Boolean

	@Upsert
	suspend fun upsert(entity: WorkMigrationLedgerEntity)
}
