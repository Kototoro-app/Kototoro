package org.skepsun.kototoro.entitygraph.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EntityGraphDao {

	@Query("SELECT * FROM `entity` WHERE id = :entityId LIMIT 1")
	abstract suspend fun findEntity(entityId: Long): EntityRecord?

	@Query("SELECT * FROM `entity` WHERE sync_id = :syncId LIMIT 1")
	abstract suspend fun findEntityBySyncId(syncId: String): EntityRecord?

	@Query("SELECT * FROM `entity` WHERE id = :entityId LIMIT 1")
	abstract fun observeEntity(entityId: Long): Flow<EntityRecord?>

	@Query("SELECT * FROM entity_preferences WHERE entity_id = :entityId LIMIT 1")
	abstract suspend fun findEntityPrefs(entityId: Long): EntityPrefsRecord?

	@Query("SELECT * FROM entity_preferences WHERE entity_id IN (:entityIds)")
	abstract suspend fun findEntityPrefsByIds(entityIds: List<Long>): List<EntityPrefsRecord>

	@Query("SELECT * FROM `entity` WHERE id IN (:entityIds)")
	abstract suspend fun findEntitiesByIds(entityIds: List<Long>): List<EntityRecord>

	@Query(
		"""
		SELECT id, type, IFNULL(sync_id, '') AS sync_id, primary_name, name_hash, aliases,
			content_type, created_at, last_accessed, access_count
		FROM `entity`
		ORDER BY id ASC
		"""
	)
	abstract suspend fun dumpEntities(): List<EntityRecord>

	@Query("SELECT id FROM `entity` WHERE type = 'WORK' AND (sync_id IS NULL OR TRIM(sync_id) = '') ORDER BY id ASC")
	abstract suspend fun findWorkEntityIdsMissingSyncId(): List<Long>

	@Query("UPDATE `entity` SET sync_id = :syncId WHERE id = :entityId")
	abstract suspend fun updateEntitySyncId(entityId: Long, syncId: String)

	@Query("SELECT * FROM entity_binding ORDER BY source ASC, external_id ASC")
	abstract suspend fun dumpBindings(): List<EntityBindingRecord>

	@Query("SELECT * FROM relation ORDER BY id ASC")
	abstract suspend fun dumpRelations(): List<RelationRecord>

	@Query("SELECT * FROM entity_preferences ORDER BY entity_id ASC")
	abstract suspend fun dumpPrefs(): List<EntityPrefsRecord>

	@Query(
		"""
		SELECT * FROM `entity`
		WHERE type = :type
		ORDER BY access_count DESC, last_accessed DESC, id DESC
		LIMIT :limit
		"""
	)
	abstract suspend fun findEntitiesByType(type: String, limit: Int): List<EntityRecord>

	@Query(
		"""
		SELECT * FROM `entity`
		WHERE type = :type AND primary_name = :primaryName
		ORDER BY access_count DESC, last_accessed DESC, id DESC
		LIMIT 1
		"""
	)
	abstract suspend fun findEntityByTypeAndPrimaryName(type: String, primaryName: String): EntityRecord?

	@Insert
	abstract suspend fun insertEntity(entity: EntityRecord): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertEntityIgnore(entity: EntityRecord): Long

	@Query(
		"""
		SELECT * FROM `entity`
		WHERE type = :type
			AND name_hash = :nameHash
			AND ((content_type = :contentType) OR (content_type IS NULL AND :contentType IS NULL))
		ORDER BY access_count DESC, last_accessed DESC, id DESC
		LIMIT 1
		""",
	)
	abstract suspend fun findEntityByTypeAndNameHashAndContentType(
		type: String,
		nameHash: Long,
		contentType: String?,
	): EntityRecord?

	@Query(
		"""
		SELECT * FROM `entity`
		WHERE type = :type AND content_type = :contentType
		ORDER BY access_count DESC, last_accessed DESC, id DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findEntitiesByTypeAndContentType(
		type: String,
		contentType: String,
		limit: Int,
	): List<EntityRecord>

	@Query(
		"""
		SELECT * FROM `entity`
		WHERE type = :type AND name_hash IN (:nameHashes)
		ORDER BY access_count DESC, last_accessed DESC, id DESC
		"""
	)
	abstract suspend fun findEntitiesByTypeAndNameHashes(type: String, nameHashes: List<Long>): List<EntityRecord>

	@Upsert
	abstract suspend fun upsertEntityRecord(entity: EntityRecord)

	@Update
	abstract suspend fun updateEntity(entity: EntityRecord)

	@Query(
		"""
		UPDATE `entity`
		SET last_accessed = :timestamp,
			access_count = access_count + 1
		WHERE id = :entityId
		"""
	)
	abstract suspend fun touchEntity(entityId: Long, timestamp: Long)

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE source = :source AND external_id = :externalId
		LIMIT 1
		"""
	)
	abstract suspend fun findBinding(source: String, externalId: String): EntityBindingRecord?

	@Query("SELECT * FROM entity_binding WHERE entity_id = :entityId")
	abstract suspend fun findBindingsByEntity(entityId: Long): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE source = :source
			AND external_id = :externalId
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		LIMIT 1
		"""
	)
	abstract suspend fun findActiveBinding(source: String, externalId: String): EntityBindingRecord?

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE entity_id = :entityId
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		"""
	)
	abstract suspend fun findActiveBindingsByEntity(entityId: Long): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE entity_id IN (:entityIds)
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		"""
	)
	abstract suspend fun findActiveBindingsByEntities(entityIds: List<Long>): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE entity_id = :entityId
			AND source IN ('local_manga', '0')
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		"""
	)
	abstract suspend fun findActiveLocalBindingsByEntity(entityId: Long): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE entity_id IN (:entityIds)
			AND source IN ('local_manga', '0')
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		"""
	)
	abstract suspend fun findActiveLocalBindingsByEntities(entityIds: List<Long>): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE source IN (:sources) AND external_id IN (:externalIds)
		"""
	)
	abstract suspend fun findBindingsBySources(
		sources: List<String>,
		externalIds: List<String>,
	): List<EntityBindingRecord>

	@Query(
		"""
		SELECT * FROM entity_binding
		WHERE source IN (:sources)
			AND external_id IN (:externalIds)
			AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		"""
	)
	abstract suspend fun findActiveBindingsBySources(
		sources: List<String>,
		externalIds: List<String>,
	): List<EntityBindingRecord>

	@Upsert
	abstract suspend fun upsertBinding(binding: EntityBindingRecord)

	@Query(
		"""
		UPDATE entity_binding
		SET state = :state,
			updated_at = :updatedAt
		WHERE source = :source AND external_id = :externalId
		"""
	)
	abstract suspend fun updateBindingState(
		source: String,
		externalId: String,
		state: String,
		updatedAt: Long,
	)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertEntityPrefsIgnore(prefs: EntityPrefsRecord): Long

	@Upsert
	abstract suspend fun upsertPrefsRecord(prefs: EntityPrefsRecord)

	@Query(
		"""
		UPDATE entity_preferences
		SET preferred_local_manga_id = :preferredLocalMangaId
			, updated_at = :updatedAt
		WHERE entity_id = :entityId
		"""
	)
	abstract suspend fun updateEntityPreferredLocalMangaId(entityId: Long, preferredLocalMangaId: Long?, updatedAt: Long)

	@Query(
		"""
		UPDATE entity_preferences
		SET metadata_source_kind = :metadataSourceKind,
			metadata_binding_source = :metadataBindingSource,
			metadata_binding_external_id = :metadataBindingExternalId,
			metadata_source_service = :metadataSourceService,
			metadata_source_remote_id = :metadataSourceRemoteId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
		"""
	)
	abstract suspend fun updateEntityMetadataSourceSelection(
		entityId: Long,
		metadataSourceKind: String?,
		metadataBindingSource: String?,
		metadataBindingExternalId: String?,
		metadataSourceService: Int?,
		metadataSourceRemoteId: Long?,
		updatedAt: Long,
	)

	@Query(
		"""
		SELECT * FROM relation
		WHERE from_entity_id = :entityId OR to_entity_id = :entityId
		ORDER BY id ASC
		"""
	)
	abstract suspend fun findRelationsForEntity(entityId: Long): List<RelationRecord>

	@Query(
		"""
		SELECT * FROM relation
		WHERE (from_entity_id = :entityId OR to_entity_id = :entityId)
			AND state IN ('ACTIVE', 'LEGACY')
		ORDER BY id ASC
		"""
	)
	abstract suspend fun findVisibleRelationsForEntity(entityId: Long): List<RelationRecord>

	@Query(
		"""
		SELECT * FROM relation
		WHERE (from_entity_id = :entityId OR to_entity_id = :entityId)
			AND (
				(
					source_binding_source = :source
					AND source_binding_external_id = :externalId
					AND origin = 'TRACKING_INGEST'
					AND state = 'ACTIVE'
				)
				OR (
					origin = 'MANUAL'
					AND state = 'ACTIVE'
				)
			)
		ORDER BY id ASC
		"""
	)
	abstract suspend fun findRelationsForEntityAndSource(
		entityId: Long,
		source: String,
		externalId: String,
	): List<RelationRecord>

	@Query(
		"""
		SELECT from_entity_id FROM relation
		WHERE to_entity_id = :entityId
			AND type = :type
		ORDER BY from_entity_id ASC
		"""
	)
	abstract suspend fun findIncomingEntityIds(entityId: Long, type: String): List<Long>

	@Query(
		"""
		SELECT from_entity_id FROM relation
		WHERE to_entity_id = :entityId
			AND type = :type
			AND state IN ('ACTIVE', 'LEGACY')
		ORDER BY from_entity_id ASC
		"""
	)
	abstract suspend fun findVisibleIncomingEntityIds(entityId: Long, type: String): List<Long>

	@Query(
		"""
		SELECT to_entity_id FROM relation
		WHERE from_entity_id = :entityId
			AND type = :type
		ORDER BY to_entity_id ASC
		"""
	)
	abstract suspend fun findOutgoingEntityIds(entityId: Long, type: String): List<Long>

	@Query(
		"""
		SELECT to_entity_id FROM relation
		WHERE from_entity_id = :entityId
			AND type = :type
			AND state IN ('ACTIVE', 'LEGACY')
		ORDER BY to_entity_id ASC
		"""
	)
	abstract suspend fun findVisibleOutgoingEntityIds(entityId: Long, type: String): List<Long>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertRelation(relation: RelationRecord): Long

	@Upsert
	abstract suspend fun upsertRelationRecord(relation: RelationRecord)

	@Query(
		"""
		SELECT id FROM relation
		WHERE from_entity_id = :fromEntityId
			AND to_entity_id = :toEntityId
			AND type = :type
		LIMIT 1
		"""
	)
	abstract suspend fun findRelationId(
		fromEntityId: Long,
		toEntityId: Long,
		type: String,
	): Long?

	@Query(
		"""
		UPDATE relation
		SET state = :state,
			updated_at = :updatedAt
		WHERE id = :relationId
		"""
	)
	abstract suspend fun updateRelationState(
		relationId: Long,
		state: String,
		updatedAt: Long,
	)

	@Query(
		"""
		SELECT id FROM `entity`
		WHERE last_accessed < :cutoffMillis
			AND access_count < :accessCountThreshold
		"""
	)
	abstract suspend fun findEntityIdsForPrune(
		cutoffMillis: Long,
		accessCountThreshold: Int,
	): List<Long>

	@Query("DELETE FROM entity_binding WHERE entity_id IN (:entityIds)")
	abstract suspend fun deleteBindingsByEntityIds(entityIds: List<Long>)

	@Query(
		"""
		DELETE FROM entity_binding
		WHERE entity_id IN (:entityIds)
			AND source IN (:sources)
		"""
	)
	abstract suspend fun deleteBindingsByEntityIdsAndSources(
		entityIds: List<Long>,
		sources: List<String>,
	): Int

	@Query(
		"""
		DELETE FROM entity_binding
		WHERE source IN (:sources)
		"""
	)
	abstract suspend fun deleteBindingsBySources(sources: List<String>): Int

	@Query(
		"""
		DELETE FROM entity_binding
		WHERE source = :source AND external_id = :externalId
		"""
	)
	abstract suspend fun deleteBindingBySource(source: String, externalId: String)

	@Query(
		"""
		DELETE FROM entity_binding
		WHERE entity_id IN (:entityIds)
			AND source IN (:sources)
			AND external_id != :externalId
		"""
	)
	abstract suspend fun deleteBindingsByEntityIdsAndSourcesExcept(
		entityIds: List<Long>,
		sources: List<String>,
		externalId: String,
	)

	@Query("DELETE FROM relation WHERE from_entity_id IN (:entityIds) OR to_entity_id IN (:entityIds)")
	abstract suspend fun deleteRelationsByEntityIds(entityIds: List<Long>)

	@Query("DELETE FROM `entity` WHERE id IN (:entityIds)")
	abstract suspend fun deleteEntitiesByIds(entityIds: List<Long>)

	@Query("DELETE FROM entity_binding")
	abstract suspend fun deleteAllBindings()

	@Query("DELETE FROM relation")
	abstract suspend fun deleteAllRelations()

	@Query("DELETE FROM entity_preferences")
	abstract suspend fun deleteAllPrefs()

	@Query("DELETE FROM `entity`")
	abstract suspend fun deleteAllEntities()
}
