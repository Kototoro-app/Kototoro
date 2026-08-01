package org.skepsun.kototoro.backups.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import org.skepsun.kototoro.backups.data.model.BackupIndex
import java.io.File
import java.util.zip.ZipInputStream

object BackupPayloadGuard {

	class UnexpectedBackupFormatException(
		val expected: BackupRestoreFormat,
		val actualAppId: String? = null,
		val actualSemanticSchemaVersion: Int? = null,
	) : IllegalArgumentException(
		"The selected backup does not match the requested restore format: expected=$expected, " +
			"appId=$actualAppId, semanticSchemaVersion=$actualSemanticSchemaVersion",
	)

	class MissingProjectionAnchorsException(
		val operation: String,
		val anchorIds: List<Long>,
	) : IllegalStateException(
		"Refusing $operation: backup has work state with missing projection anchors: " +
			anchorIds.joinToString(),
	)

	class WorkEntityMissingSyncIdException(
		val operation: String,
		val entityId: Long,
	) : IllegalStateException(
		"Refusing $operation: backup has a WORK entity without sync_id: id=$entityId.",
	)

	data class Inspection(
		val sectionBytes: Map<BackupSection, Int>,
		val unknownEntries: Map<String, Int>,
	) {
		fun describe(): String {
			val known = BackupSection.entries
				.filter { it in sectionBytes }
				.joinToString { section -> "${section.name}:${sectionBytes.getValue(section)}b" }
			val unknown = unknownEntries.entries.joinToString { (name, bytes) -> "$name:${bytes}b" }
			return listOf(known, unknown)
				.filter { it.isNotBlank() }
				.joinToString()
		}
	}

	fun inspect(file: File): Inspection {
		val sectionBytes = LinkedHashMap<BackupSection, Int>()
		val unknownEntries = LinkedHashMap<String, Int>()
		ZipInputStream(file.inputStream()).use { input ->
			var entry = input.nextEntry
			while (entry != null) {
				val bytes = input.readBytes().size
				val section = BackupSection.of(entry)
				if (section != null) {
					sectionBytes[section] = bytes
				} else {
					unknownEntries[entry.name] = bytes
				}
				input.closeEntry()
				entry = input.nextEntry
			}
		}
		return Inspection(sectionBytes, unknownEntries)
	}

	fun requireRestorableWorkSnapshot(file: File, operation: String): Inspection {
		return inspect(file).also { inspection ->
			if (inspection.isIdentityOnlyWorkSnapshot()) {
				throw IllegalStateException(
					"Refusing $operation: backup snapshot contains entity identity data but no work " +
						"favourites, history, or statistics. This incomplete snapshot would clear local user state.",
				)
			}
			validateWorkSnapshotSemantics(file, operation)
		}
	}

	fun requireRestoreFormat(file: File, expected: BackupRestoreFormat): BackupIndex {
		val index = readBackupIndex(file) ?: throw UnexpectedBackupFormatException(expected)
		val matches = when (expected) {
			BackupRestoreFormat.KOTOTORO_CURRENT ->
				index.appId.isKototoroApplicationId() &&
					index.semanticSchemaVersion == BackupIndex.CURRENT_SYNC_SCHEMA_VERSION

			BackupRestoreFormat.KOTATSU_OR_LEGACY_KOTOTORO ->
				index.semanticSchemaVersion < BackupIndex.CURRENT_SYNC_SCHEMA_VERSION
		}
		if (!matches) {
			throw UnexpectedBackupFormatException(
				expected = expected,
				actualAppId = index.appId,
				actualSemanticSchemaVersion = index.semanticSchemaVersion,
			)
		}
		return index
	}

	private fun String.isKototoroApplicationId(): Boolean {
		return this in KOTOTORO_APPLICATION_IDS
	}

	private fun readBackupIndex(file: File): BackupIndex? {
		ZipInputStream(file.inputStream()).use { input ->
			var entry = input.nextEntry
			while (entry != null) {
				if (BackupSection.of(entry) == BackupSection.INDEX) {
					return runCatching {
						json.decodeFromString<List<BackupIndex>>(input.readBytes().decodeToString()).single()
					}.getOrNull()
				}
				input.closeEntry()
				entry = input.nextEntry
			}
		}
		return null
	}

	private fun validateWorkSnapshotSemantics(file: File, operation: String) {
		val sections = readJsonSections(file)
		val hasWorkState = sections[BackupSection.WORK_FAVOURITES].hasItems() ||
			sections[BackupSection.WORK_HISTORY].hasItems() ||
			sections[BackupSection.WORK_STATS].hasItems()
		if (!hasWorkState) {
			return
		}

		val categoryIds = sections[BackupSection.CATEGORIES].ids("category_id")
		val favouriteCategoryIds = sections[BackupSection.WORK_FAVOURITES].ids("category_id")
		val missingCategoryIds = favouriteCategoryIds - categoryIds
		if (missingCategoryIds.isNotEmpty()) {
			throw IllegalStateException(
				"Refusing $operation: backup snapshot has work favourites with missing category ids: " +
					missingCategoryIds.take(MAX_REPORTED_IDS).joinToString(),
			)
		}

		val activeFavouriteWithoutAnchor = sections[BackupSection.WORK_FAVOURITES]
			.orEmpty()
			.firstOrNull { item ->
				item.long("deleted_at") == 0L && item.longOrNull("anchor_manga_id") == null
			}
		if (activeFavouriteWithoutAnchor != null) {
			throw IllegalStateException(
				"Refusing $operation: backup has an active work favourite without projection anchor " +
					"for entity_id=${activeFavouriteWithoutAnchor.long("entity_id")}.",
			)
		}

		val entityIds = sections[BackupSection.ENTITY_GRAPH_ENTITIES].ids("id")
		val workStateEntityIds = sections[BackupSection.WORK_FAVOURITES].ids("entity_id") +
			sections[BackupSection.WORK_HISTORY].ids("entity_id") +
			sections[BackupSection.WORK_STATS].ids("entity_id")
		val missingEntityIds = workStateEntityIds - entityIds
		if (missingEntityIds.isNotEmpty()) {
			throw IllegalStateException(
				"Refusing $operation: backup snapshot has work state with missing entity ids: " +
					missingEntityIds.take(MAX_REPORTED_IDS).joinToString(),
			)
		}

		val projectionIds = sections[BackupSection.PROJECTIONS].ids("id")
		val anchorIds = sections[BackupSection.WORK_FAVOURITES].activeIds("anchor_manga_id") +
			sections[BackupSection.WORK_HISTORY].activeIds("anchor_manga_id") +
			sections[BackupSection.WORK_STATS].ids("anchor_manga_id")
		val missingAnchorIds = anchorIds - projectionIds
		if (missingAnchorIds.isNotEmpty()) {
			throw MissingProjectionAnchorsException(
				operation = operation,
				anchorIds = missingAnchorIds.take(MAX_REPORTED_IDS),
			)
		}

		val workEntities = sections[BackupSection.ENTITY_GRAPH_ENTITIES]
			.orEmpty()
			.filter { it.string("type") == "WORK" }
		val blankSyncIdEntity = workEntities.firstOrNull { it.string("sync_id", "syncId").isBlank() }
		if (blankSyncIdEntity != null) {
			throw WorkEntityMissingSyncIdException(
				operation = operation,
				entityId = blankSyncIdEntity.long("id"),
			)
		}
		val duplicateSyncId = workEntities
			.map { it.string("sync_id", "syncId") }
			.filter { it.isNotBlank() }
			.groupingBy { it }
			.eachCount()
			.entries
			.firstOrNull { it.value > 1 }
			?.key
		if (duplicateSyncId != null) {
			throw IllegalStateException(
				"Refusing $operation: backup snapshot has duplicate WORK sync_id: $duplicateSyncId.",
			)
		}
	}

	private fun readJsonSections(file: File): Map<BackupSection, JsonArray> {
		val sections = LinkedHashMap<BackupSection, JsonArray>()
		ZipInputStream(file.inputStream()).use { input ->
			var entry = input.nextEntry
			while (entry != null) {
				val section = BackupSection.of(entry)
				if (section in SEMANTIC_GUARD_SECTIONS) {
					val text = input.readBytes().decodeToString()
					sections[section!!] = runCatching {
						json.parseToJsonElement(text).jsonArray
					}.getOrElse { error ->
						throw IllegalStateException("Refusing backup snapshot inspection: invalid JSON in ${section.entryName}.", error)
					}
				} else {
					input.readBytes()
				}
				input.closeEntry()
				entry = input.nextEntry
			}
		}
		return sections
	}

	private fun JsonArray?.hasItems(): Boolean = this != null && isNotEmpty()

	private fun JsonArray?.ids(name: String): Set<Long> {
		if (this == null) return emptySet()
		return mapNotNullTo(LinkedHashSet()) { it.longOrNull(name)?.takeIf { id -> id > 0L } }
	}

	private fun JsonArray?.activeIds(name: String): Set<Long> {
		if (this == null) return emptySet()
		return mapNotNullTo(LinkedHashSet()) { item ->
			item.longOrNull(name)
				?.takeIf { id -> id > 0L && item.long("deleted_at") == 0L }
		}
	}

	private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

	private fun JsonElement.long(name: String): Long = longOrNull(name) ?: 0L

	private fun JsonElement.longOrNull(name: String): Long? {
		return (this as? JsonObject)
			?.get(name)
			?.jsonPrimitive
			?.let { primitive ->
				primitive.contentOrNull?.toLongOrNull()
					?: primitive.intOrNull?.toLong()
			}
	}

	private fun JsonElement.string(name: String): String {
		return (this as? JsonObject)
			?.get(name)
			?.jsonPrimitive
			?.contentOrNull
			.orEmpty()
	}

	private fun JsonElement.string(vararg names: String): String {
		return names.firstNotNullOfOrNull { name ->
			(this as? JsonObject)
				?.get(name)
				?.jsonPrimitive
				?.contentOrNull
		}.orEmpty()
	}

	private fun Inspection.isIdentityOnlyWorkSnapshot(): Boolean {
		val hasAuthoritativeIdentity = bytesOf(BackupSection.ENTITY_GRAPH_ENTITIES) > EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.ENTITY_GRAPH_BINDINGS) > EMPTY_JSON_ARRAY_BYTES
		if (!hasAuthoritativeIdentity) {
			return false
		}
		return bytesOf(BackupSection.WORK_HISTORY) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.WORK_FAVOURITES) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.WORK_STATS) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.HISTORY) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.FAVOURITES) <= EMPTY_JSON_ARRAY_BYTES &&
			bytesOf(BackupSection.STATS) <= EMPTY_JSON_ARRAY_BYTES
	}

	private fun Inspection.bytesOf(section: BackupSection): Int {
		return sectionBytes[section] ?: 0
	}

	private const val EMPTY_JSON_ARRAY_BYTES = 2
	private const val MAX_REPORTED_IDS = 8
	private val KOTOTORO_APPLICATION_IDS = setOf(
		"org.skepsun.kototoro",
		"org.skepsun.kototoro.debug",
		"org.skepsun.kototoro.nightly",
	)
	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
	}
	private val SEMANTIC_GUARD_SECTIONS = setOf(
		BackupSection.CATEGORIES,
		BackupSection.PROJECTIONS,
		BackupSection.ENTITY_GRAPH_ENTITIES,
		BackupSection.WORK_HISTORY,
		BackupSection.WORK_FAVOURITES,
		BackupSection.WORK_STATS,
	)
}
