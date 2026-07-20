package org.skepsun.kototoro.work.domain

import org.skepsun.kototoro.parsers.model.Content

enum class WorkProjectionBindingAction {
	ATTACHED,
	REUSED,
	MERGED_SINGLE_PROJECTION_WORK,
	MOVED_PROJECTION,
}

enum class WorkProjectionBindingConflict {
	TARGET_ENTITY_MISSING,
	TARGET_CONTENT_TYPE_CONFLICT,
	SOURCE_IDENTITY_INVALID,
	OWNER_CHANGED,
	BINDING_FAILED,
}

sealed interface WorkProjectionBindingResult {
	data class Success(
		val entityId: Long,
		val projection: Content,
		val action: WorkProjectionBindingAction,
	) : WorkProjectionBindingResult

	data class Conflict(
		val reason: WorkProjectionBindingConflict,
		val targetEntityId: Long,
		val sourceEntityId: Long? = null,
		val projectionId: Long? = null,
	) : WorkProjectionBindingResult
}
