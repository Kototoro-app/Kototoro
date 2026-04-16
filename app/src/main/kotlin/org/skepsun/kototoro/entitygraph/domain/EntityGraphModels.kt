package org.skepsun.kototoro.entitygraph.domain

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
	val coverUrl: String? = null,
	val description: String? = null,
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
	val aliases: List<String> = emptyList(),
	val coverUrl: String? = null,
	val description: String? = null,
	val externalLinks: Map<String, String> = emptyMap(),
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
