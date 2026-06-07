package org.skepsun.kototoro.entitygraph.domain

import org.skepsun.kototoro.parsers.model.ContentType

enum class EntityType {
	WORK,
	CHARACTER,
	PERSON,
	ORGANIZATION,
}

data class Entity(
	val id: Long,
	val type: EntityType,
	val primaryName: String,
	val aliases: List<String>,
	val createdAt: Long,
	val lastAccessed: Long,
	val accessCount: Int,
)

data class EntityBinding(
	val entityId: Long,
	val source: String,
	val externalId: String,
	val confidence: Float,
	val isPrimary: Boolean,
)

enum class RelationType {
	HAS_CHARACTER,
	VOICED_BY,
	CREATED_BY,
	BELONGS_TO,
	RELATED_TO,
}

data class Relation(
	val id: Long,
	val fromEntityId: Long,
	val toEntityId: Long,
	val type: RelationType,
	val weight: Float,
	val createdAt: Long,
)

data class TrackingWorkDto(
	val externalId: String,
	val primaryName: String,
	val contentType: ContentType? = null,
	val aliases: List<String> = emptyList(),
	val characters: List<TrackingCharacterDto> = emptyList(),
	val staff: List<TrackingStaffDto> = emptyList(),
)

data class TrackingCharacterDto(
	val externalId: String? = null,
	val primaryName: String,
	val aliases: List<String> = emptyList(),
	val voiceActors: List<TrackingPersonDto> = emptyList(),
)

data class TrackingStaffDto(
	val externalId: String? = null,
	val primaryName: String,
	val aliases: List<String> = emptyList(),
	val role: String? = null,
)

data class TrackingPersonDto(
	val externalId: String? = null,
	val primaryName: String,
	val aliases: List<String> = emptyList(),
)

enum class EntityBindingStrength {
	AUTO_BIND,
	WEAK_BIND,
	IGNORE,
}

/**
 * Normalise a name for case-insensitive, whitespace-insensitive, punctuation-stripped comparison.
 * Canonical implementation shared across entitygraph, favourites, and tracking modules.
 *
 * Rules: lowercase, collapse whitespace, strip all non-alphanumeric/CJK characters.
 */
public val NAME_NORMALIZE_REGEX = Regex("[^a-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9d]")

public fun normalizeEntityName(value: String): String {
	return value.lowercase()
		.replace(Regex("\\s+"), "")
		.replace(NAME_NORMALIZE_REGEX, "")
}
