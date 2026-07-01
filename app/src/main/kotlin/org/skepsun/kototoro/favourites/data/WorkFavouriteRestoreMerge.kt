package org.skepsun.kototoro.favourites.data

/**
 * Merge policy for two [WorkFavouriteEntity] rows that collapse onto the same
 * `(entity_id, category_id)` key during restore / entity remap.
 *
 * Mirrors the migration merge policy defined in
 * `docs/architecture/entity-identity-migration-consolidation-plan-2026-06.md`:
 * - newest `updated_at` decides the active/delete state (and its anchor / sort key);
 * - `created_at` takes the earliest of the two;
 * - `pinned` is OR'd across rows that are still active (`deleted_at == 0`).
 *
 * The returned row keeps the key (`entity_id`, `category_id`) of [base], so callers
 * must pass rows that already share the target key.
 */
fun mergeRestoredWorkFavourites(
	base: WorkFavouriteEntity,
	other: WorkFavouriteEntity,
): WorkFavouriteEntity {
	val newest = if (base.updatedAt >= other.updatedAt) base else other
	val activePinned = (base.deletedAt == 0L && base.isPinned) ||
		(other.deletedAt == 0L && other.isPinned)
	return stabilizeActiveWorkFavouriteAnchor(
		merged = newest.copy(
			entityId = base.entityId,
			categoryId = base.categoryId,
			createdAt = minOf(base.createdAt, other.createdAt),
			isPinned = activePinned,
		),
		base,
		other,
	)
}

fun stabilizeActiveWorkFavouriteAnchor(
	merged: WorkFavouriteEntity,
	vararg candidates: WorkFavouriteEntity,
): WorkFavouriteEntity {
	if (merged.deletedAt != 0L || merged.anchorMangaId != null) {
		return merged
	}
	val fallbackAnchor = candidates
		.asSequence()
		.filter { it.deletedAt == 0L }
		.sortedByDescending { it.updatedAt }
		.mapNotNull(WorkFavouriteEntity::anchorMangaId)
		.firstOrNull()
		?: candidates.firstOrNull { it.anchorMangaId != null }?.anchorMangaId
	return if (fallbackAnchor != null) {
		merged.copy(anchorMangaId = fallbackAnchor)
	} else {
		merged
	}
}
