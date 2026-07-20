package org.skepsun.kototoro.entitygraph.data

import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.work.domain.isWorkContentTypeCompatibleWith

/**
 * Work content type is an identity boundary. A null value represents legacy or
 * unresolved data and must never be upgraded by title matching alone.
 */
internal fun EntityRecord.acceptsContentType(requested: ContentType?): Boolean {
	return type != EntityType.WORK.name ||
		(requested != null && contentType != null && contentType == requested.name)
}

internal fun EntityRecord.acceptsCompatibleWorkContentType(requested: ContentType?): Boolean {
	if (type != EntityType.WORK.name) {
		return true
	}
	val current = contentType?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
	return current.isWorkContentTypeCompatibleWith(requested)
}

internal fun Entity.canAutoBindContentType(requested: ContentType?): Boolean {
	return type != EntityType.WORK ||
		(requested != null && contentType != null && contentType == requested)
}

internal fun EntityRecord.withContentType(contentType: ContentType?): EntityRecord {
	return if (contentType == null || this.contentType != null) {
		this
	} else {
		copy(contentType = contentType.name)
	}
}

internal fun Collection<EntityRecord>.canMergeWorkContentTypes(
	allowCompatibleContentTypes: Boolean = false,
): Boolean {
	val workTypes = filter { it.type == EntityType.WORK.name }
	if (workTypes.isEmpty()) return true
	val contentTypes = workTypes.map { record ->
		record.contentType?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
	}
	if (contentTypes.any { it == null }) return false
	return if (allowCompatibleContentTypes) {
		val first = contentTypes.first()
		contentTypes.all { first.isWorkContentTypeCompatibleWith(it) }
	} else {
		contentTypes.distinct().size == 1
	}
}
