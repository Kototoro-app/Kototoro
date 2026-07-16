package org.skepsun.kototoro.entitygraph.domain

import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

enum class EntityType {
	WORK,
	CHARACTER,
	PERSON,
	ORGANIZATION,
}

data class Entity(
	val id: Long,
	val type: EntityType,
	val contentType: ContentType? = null,
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
	val sourceKindName: String = source.toEntityBindingSourceKind().name,
	val stateName: String = EntityBindingState.CONFIRMED.name,
	val createdBy: String = EntityBindingCreatedBy.LEGACY.name,
	val updatedAt: Long = 0L,
)

enum class EntityBindingSourceKind {
	READING_SOURCE,
	TRACKING_SOURCE,
	UNKNOWN,
}

enum class EntityBindingState {
	MANUAL,
	CONFIRMED,
	CANDIDATE,
	REJECTED,
	LEGACY,
}

enum class EntityBindingCreatedBy {
	USER,
	MATCHER,
	INGEST,
	SYNC,
	MIGRATION,
	LEGACY,
}

val EntityBinding.sourceKind: EntityBindingSourceKind
	get() = sourceKindName.toEntityBindingSourceKindOrNull() ?: source.toEntityBindingSourceKind()

val EntityBinding.state: EntityBindingState
	get() = stateName.toEntityBindingStateOrNull() ?: EntityBindingState.LEGACY

fun EntityBinding.isLocalReadingSource(): Boolean {
	return sourceKind == EntityBindingSourceKind.READING_SOURCE || source.isLocalEntityBindingSource()
}

fun EntityBinding.trackingServiceOrNull(): ScrobblerService? {
	if (sourceKind != EntityBindingSourceKind.TRACKING_SOURCE) {
		return null
	}
	return source.toTrackingServiceOrNull()
}

fun String.isLocalEntityBindingSource(): Boolean {
	return this == "0" || this == "local_manga"
}

fun String.toTrackingServiceOrNull(): ScrobblerService? {
	val raw = trim()
	if (raw.isBlank()) {
		return null
	}
	return raw.toIntOrNull()?.let { id ->
		ScrobblerService.entries.firstOrNull { it.id == id }
	} ?: ScrobblerService.entries.firstOrNull {
		it.name.equals(raw, ignoreCase = true)
	}
}

fun String.toEntityBindingSourceKind(): EntityBindingSourceKind {
	return when {
		isLocalEntityBindingSource() -> EntityBindingSourceKind.READING_SOURCE
		toTrackingServiceOrNull() != null -> EntityBindingSourceKind.TRACKING_SOURCE
		else -> EntityBindingSourceKind.UNKNOWN
	}
}

fun String.toEntityBindingSourceKindOrNull(): EntityBindingSourceKind? {
	return EntityBindingSourceKind.entries.firstOrNull { it.name == this }
}

fun String.toEntityBindingStateOrNull(): EntityBindingState? {
	return EntityBindingState.entries.firstOrNull { it.name == this }
}

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
	val sourceBindingSource: String? = null,
	val sourceBindingExternalId: String? = null,
	val originName: String = EntityRelationOrigin.LEGACY.name,
	val stateName: String = EntityRelationState.LEGACY.name,
	val updatedAt: Long = 0L,
)

enum class EntityRelationOrigin {
	TRACKING_INGEST,
	MANUAL,
	MIGRATION,
	LEGACY,
}

enum class EntityRelationState {
	ACTIVE,
	HIDDEN,
	REJECTED,
	LEGACY,
}

enum class EntityGraphRepairIssueKind {
	ORPHAN_PREFERRED_LOCAL,
	ORPHAN_METADATA_SOURCE,
	REDUNDANT_PROJECTION_METADATA_SELECTION,
	REDUNDANT_PROJECTION_OVERRIDE,
	REDUNDANT_PROJECTION_READING_STATUS,
	CONFLICTING_READING_BINDING,
	STALE_LEGACY_RELATION,
	STALE_TRACKING_CACHE_LINK,
	SUSPECT_MISMERGED_LOCAL_WORK,
	SUSPECT_TRACKING_BINDING,
	SUSPECT_METADATA_SOURCE,
	DANGLING_WORK_PROJECTION_ANCHOR,
	WORK_ENTITY_MISSING_SYNC_ID,
	MIXED_WORK_CONTENT_TYPES,
}

data class EntityGraphRepairIssue(
	val kind: EntityGraphRepairIssueKind,
	val entityId: Long,
	val source: String? = null,
	val externalId: String? = null,
	val localMangaId: Long? = null,
	val relationId: Long? = null,
	val count: Int = 1,
)

data class EntityGraphRepairReport(
	val issues: List<EntityGraphRepairIssue>,
) {
	val orphanPreferredLocalCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.ORPHAN_PREFERRED_LOCAL }
	val orphanMetadataSourceCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.ORPHAN_METADATA_SOURCE }
	val redundantProjectionMetadataSelectionCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_METADATA_SELECTION }
	val redundantProjectionOverrideCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_OVERRIDE }
	val redundantProjectionReadingStatusCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_READING_STATUS }
	val conflictingReadingBindingCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.CONFLICTING_READING_BINDING }
	val staleLegacyRelationCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.STALE_LEGACY_RELATION }
	val staleTrackingCacheLinkCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.STALE_TRACKING_CACHE_LINK }
	val suspectMismergedLocalWorkCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.SUSPECT_MISMERGED_LOCAL_WORK }
	val suspectTrackingBindingCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.SUSPECT_TRACKING_BINDING }
	val suspectMetadataSourceCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.SUSPECT_METADATA_SOURCE }
	val danglingWorkProjectionAnchorCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.DANGLING_WORK_PROJECTION_ANCHOR }
	val workEntityMissingSyncIdCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.WORK_ENTITY_MISSING_SYNC_ID }
	val mixedWorkContentTypeEntityCount: Int
		get() = issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES }
			.map { it.entityId }
			.distinct()
			.count()
	val mixedWorkContentTypeProjectionCount: Int
		get() = issues.count { it.kind == EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES }
	val hasIssues: Boolean
		get() = issues.isNotEmpty()
}

val Relation.origin: EntityRelationOrigin
	get() = originName.toEntityRelationOriginOrNull() ?: EntityRelationOrigin.LEGACY

val Relation.state: EntityRelationState
	get() = stateName.toEntityRelationStateOrNull() ?: EntityRelationState.LEGACY

fun String.toEntityRelationOriginOrNull(): EntityRelationOrigin? {
	return EntityRelationOrigin.entries.firstOrNull { it.name == this }
}

fun String.toEntityRelationStateOrNull(): EntityRelationState? {
	return EntityRelationState.entries.firstOrNull { it.name == this }
}

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

public fun normalizeStrictTitleKey(value: String): String {
	return value.trim()
		.lowercase()
		.replace(Regex("[\\p{P}\\p{S}]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()
}

public fun normalizeStrictTitleKey(value: String, sourceNames: Iterable<String>): String {
	return normalizeStrictTitleKey(stripTrailingSourceTitleSuffix(value, sourceNames))
}

public fun stripTrailingSourceTitleSuffix(value: String, sourceNames: Iterable<String>): String {
	val title = value.trim()
	val match = TRAILING_SOURCE_TITLE_SUFFIX_REGEX.matchEntire(title) ?: return title
	val suffixKey = normalizeStrictTitleKey(match.groupValues[2])
	if (suffixKey.isBlank()) {
		return title
	}
	val sourceKeys = sourceNames
		.mapTo(LinkedHashSet()) { normalizeStrictTitleKey(it) }
		.filterTo(LinkedHashSet()) { it.isNotBlank() }
	return if (suffixKey in sourceKeys) {
		match.groupValues[1].trim()
	} else {
		title
	}
}

public fun stripEntityDisambiguationTitleSuffix(value: String, sourceNames: Iterable<String>): String {
	return stripTrailingSourceTitleSuffix(
		value = value,
		sourceNames = sourceNames.flatMap(::sourceDisambiguationKeys),
	)
}

private fun sourceDisambiguationKeys(sourceName: String): List<String> {
	val trimmed = sourceName.trim()
	if (trimmed.isBlank()) {
		return emptyList()
	}
	return buildList {
		add(trimmed)
		add(trimmed.substringAfterLast('/'))
		add(trimmed.substringAfterLast(':'))
		add(trimmed.substringAfterLast('.'))
	}
}

private val TRAILING_SOURCE_TITLE_SUFFIX_REGEX = Regex("""^(.+?)\s*[\(（]([^\(\)（）]+)[\)）]\s*$""")
