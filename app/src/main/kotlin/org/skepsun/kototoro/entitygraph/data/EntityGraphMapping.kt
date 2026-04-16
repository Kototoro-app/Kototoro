package org.skepsun.kototoro.entitygraph.data

import org.json.JSONArray
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.Relation
import org.skepsun.kototoro.entitygraph.domain.RelationType

internal fun EntityRecord.toModel(): Entity = Entity(
	id = id,
	type = EntityType.valueOf(type),
	primaryName = primaryName,
	aliases = decodeStringList(aliases),
	coverUrl = coverUrl,
	description = description,
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
)

internal fun RelationRecord.toModel(): Relation = Relation(
	id = id,
	fromEntityId = fromEntityId,
	toEntityId = toEntityId,
	type = RelationType.valueOf(type),
	weight = weight,
	createdAt = createdAt,
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
