package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.parsers.model.ContentType

/**
 * A work details page may only expose projections from the same content-type
 * family as the currently selected projection and, when present, its Space.
 * Unknown types are rejected so legacy data cannot widen the result set.
 */
internal fun isDetailsProjectionAllowed(
	currentType: ContentType?,
	projectionType: ContentType?,
	spaceAllowedTypes: Set<ContentType>?,
): Boolean {
	return currentType != null &&
		projectionType == currentType &&
		(spaceAllowedTypes == null || projectionType in spaceAllowedTypes)
}
