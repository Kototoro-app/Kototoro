package org.skepsun.kototoro.work.domain

data class WorkIdentity(
	val entityId: Long?,
	val requestedMangaId: Long?,
	val preferredMangaId: Long?,
	val localMangaIds: Set<Long>,
	val migrationState: WorkMigrationState,
)

enum class WorkMigrationState {
	VALID,
	NEEDS_REVIEW,
}
