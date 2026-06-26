package org.skepsun.kototoro.stats.data

/**
 * Merge policy for two [WorkStatsEntity] rows that collapse onto the same
 * `(entity_id, started_at)` key during restore / entity remap.
 *
 * Stats have no stable event id, so the migration plan in
 * `docs/architecture/entity-identity-migration-consolidation-plan-2026-06.md`
 * de-duplicates by `(entity_id, anchor_manga_id, started_at, duration, pages)`
 * and prefers not to under-count. When two rows share the same primary key we
 * keep the larger observation (more pages, then longer duration) rather than
 * dropping data.
 */
fun mergeRestoredWorkStats(
	base: WorkStatsEntity,
	other: WorkStatsEntity,
): WorkStatsEntity {
	val winner = compareValuesBy(other, base, { it.pages }, { it.duration })
		.takeIf { it > 0 }
		?.let { other }
		?: base
	return winner.copy(
		entityId = base.entityId,
		startedAt = base.startedAt,
	)
}
