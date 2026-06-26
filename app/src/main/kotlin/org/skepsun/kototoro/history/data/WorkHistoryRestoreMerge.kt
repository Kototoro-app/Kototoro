package org.skepsun.kototoro.history.data

/**
 * Merge policy for two [WorkHistoryEntity] rows that collapse onto the same
 * `entity_id` (the table primary key) during restore / entity remap.
 *
 * Mirrors the migration merge policy in
 * `docs/architecture/entity-identity-migration-consolidation-plan-2026-06.md`:
 * the newest `updated_at` wins the current reading position and keeps its
 * `anchor_manga_id`; `created_at` takes the earliest of the two.
 */
fun mergeRestoredWorkHistory(
	base: WorkHistoryEntity,
	other: WorkHistoryEntity,
): WorkHistoryEntity {
	val newest = if (base.updatedAt >= other.updatedAt) base else other
	return newest.copy(
		entityId = base.entityId,
		createdAt = minOf(base.createdAt, other.createdAt),
	)
}
