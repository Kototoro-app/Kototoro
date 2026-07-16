package org.skepsun.kototoro.entitygraph.data

import org.json.JSONArray
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.Relation
import org.skepsun.kototoro.entitygraph.domain.RelationType
import org.skepsun.kototoro.entitygraph.domain.isLocalEntityBindingSource
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingStateOrNull
import org.skepsun.kototoro.entitygraph.domain.normalizeEntityName
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.model.ContentType

internal fun EntityRecord.toModel(): Entity = Entity(
	id = id,
	type = runCatching { EntityType.valueOf(type) }.getOrDefault(EntityType.WORK),
	contentType = contentType?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() },
	primaryName = primaryName,
	aliases = decodeStringList(aliases),
	createdAt = createdAt,
	lastAccessed = lastAccessed,
	accessCount = accessCount,
)

internal fun EntityBindingRecord.toModel(): EntityBinding = EntityBinding(
	entityId = entityId,
	source = source,
	externalId = externalId,
	confidence = confidence,
	isPrimary = isPrimary,
	sourceKindName = sourceKind,
	stateName = state,
	createdBy = createdBy,
	updatedAt = updatedAt,
)

fun EntityBindingRecord.isLocalReadingSource(): Boolean {
	return source.isLocalEntityBindingSource()
}

fun EntityBindingRecord.isActiveBinding(): Boolean {
	return state.toEntityBindingStateOrNull() in ACTIVE_BINDING_STATES
}

private val ACTIVE_BINDING_STATES = setOf(
	EntityBindingState.MANUAL,
	EntityBindingState.CONFIRMED,
	EntityBindingState.LEGACY,
)

internal fun RelationRecord.toModel(): Relation = Relation(
	id = id,
	fromEntityId = fromEntityId,
	toEntityId = toEntityId,
	type = RelationType.valueOf(type),
	weight = weight,
	createdAt = createdAt,
	sourceBindingSource = sourceBindingSource.takeIf { it.isNotBlank() },
	sourceBindingExternalId = sourceBindingExternalId.takeIf { it.isNotBlank() },
	originName = origin,
	stateName = state,
	updatedAt = updatedAt,
)

internal fun encodeStringList(values: Collection<String>): String? {
	val normalized = values
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.distinct()
	if (normalized.isEmpty()) {
		return null
	}
	return JSONArray(normalized).toString()
}

internal fun decodeStringList(raw: String?): List<String> {
	if (raw.isNullOrBlank()) {
		return emptyList()
	}
	return runCatching {
		JSONArray(raw).let { json ->
			buildList(json.length()) {
				for (index in 0 until json.length()) {
					val value = json.optString(index).trim()
					if (value.isNotEmpty()) {
						add(value)
					}
				}
			}
		}
	}.getOrElse { emptyList() }
}

internal fun mergeAliases(primaryName: String, aliases: Collection<String>): List<String> {
	return buildList {
		add(primaryName)
		addAll(aliases)
	}.map { it.trim() }
		.filter { it.isNotEmpty() }
		.distinct()
}


/**
 * Normalise a name for case-insensitive, whitespace-insensitive, punctuation-stripped comparison.
 * Used by binding matchers and source adapters.
 */
internal fun normalizeName(value: String): String = normalizeEntityName(value)

/**
 * Compute a deterministic 64-bit hash of the normalised primary name.
 * Used as a dedup key with a UNIQUE constraint on (type, name_hash).
 */
internal fun computeNameHash(primaryName: String): Long {
	return normalizeName(primaryName.trim()).longHashCode()
}

internal fun computeProjectionSyncId(source: String, externalId: String): String {
	val normalizedSource = source.trim()
	val normalizedExternalId = externalId.trim()
	return "projection:${normalizedSource.length}:$normalizedSource:${normalizedExternalId.length}:$normalizedExternalId"
}

internal fun EntityRecord.resolveProjectionSyncId(
	projectionSyncId: String,
	conflictingEntityId: Long?,
): String {
	if (conflictingEntityId == null || conflictingEntityId == id) {
		return projectionSyncId
	}
	return syncId.ifBlank { java.util.UUID.randomUUID().toString() }
}

internal fun EntityBindingRecord.isAuthoritativeProjectionBinding(): Boolean {
	if (source.isLocalEntityBindingSource()) {
		return false
	}
	return sourceKind != EntityBindingSourceKind.TRACKING_SOURCE.name
}
