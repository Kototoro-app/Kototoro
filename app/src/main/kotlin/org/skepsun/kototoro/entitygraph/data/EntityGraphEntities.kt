package org.skepsun.kototoro.entitygraph.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityRelationOrigin
import org.skepsun.kototoro.entitygraph.domain.EntityRelationState
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingSourceKind
import java.util.UUID

@Serializable
@Entity(
	tableName = TABLE_ENTITY_GRAPH_ENTITY,
	indices = [
		Index(name = "idx_entity_name", value = ["primary_name"]),
		Index(name = "idx_entity_name_hash", value = ["type", "name_hash", "content_type"], unique = true),
		Index(name = "idx_entity_sync_id", value = ["sync_id"], unique = true),
		Index(name = "idx_entity_type_access", value = ["type", "access_count", "last_accessed", "id"]),
	],
)
data class EntityRecord(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "type") val type: String,
	@ColumnInfo(name = "content_type") val contentType: String? = null,
	@ColumnInfo(name = "sync_id", defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
	@ColumnInfo(name = "primary_name") val primaryName: String,
	@ColumnInfo(name = "name_hash") val nameHash: Long = 0L,
	@ColumnInfo(name = "aliases") val aliases: String?,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "last_accessed") val lastAccessed: Long,
	@ColumnInfo(name = "access_count") val accessCount: Int,
)

@Serializable
@Entity(
	tableName = TABLE_ENTITY_GRAPH_BINDING,
	primaryKeys = ["source", "external_id"],
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(name = "idx_binding_entity", value = ["entity_id"]),
		Index(name = "idx_binding_external", value = ["source", "external_id"]),
	],
)
data class EntityBindingRecord(
	@ColumnInfo(name = "entity_id") val entityId: Long,
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "external_id") val externalId: String,
	@ColumnInfo(name = "confidence") val confidence: Float,
	@ColumnInfo(name = "is_primary") val isPrimary: Boolean,
	@ColumnInfo(name = "source_kind", defaultValue = "'UNKNOWN'")
	val sourceKind: String = source.toEntityBindingSourceKind().name,
	@ColumnInfo(name = "state", defaultValue = "'CONFIRMED'")
	val state: String = EntityBindingState.CONFIRMED.name,
	@ColumnInfo(name = "created_by", defaultValue = "'LEGACY'")
	val createdBy: String = EntityBindingCreatedBy.LEGACY.name,
	@ColumnInfo(name = "updated_at", defaultValue = "0")
	val updatedAt: Long = 0L,
)

@Serializable
@Entity(
	tableName = TABLE_ENTITY_GRAPH_RELATION,
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["from_entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["to_entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(name = "idx_relation_from", value = ["from_entity_id"]),
		Index(name = "idx_relation_to", value = ["to_entity_id"]),
		Index(
			name = "idx_relation_unique",
			value = [
				"from_entity_id",
				"to_entity_id",
				"type",
				"source_binding_source",
				"source_binding_external_id",
				"origin",
			],
			unique = true,
		),
	],
)
data class RelationRecord(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "from_entity_id") val fromEntityId: Long,
	@ColumnInfo(name = "to_entity_id") val toEntityId: Long,
	@ColumnInfo(name = "type") val type: String,
	@ColumnInfo(name = "weight") val weight: Float,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "source_binding_source", defaultValue = "''")
	val sourceBindingSource: String = "",
	@ColumnInfo(name = "source_binding_external_id", defaultValue = "''")
	val sourceBindingExternalId: String = "",
	@ColumnInfo(name = "origin", defaultValue = "'LEGACY'")
	val origin: String = EntityRelationOrigin.LEGACY.name,
	@ColumnInfo(name = "state", defaultValue = "'LEGACY'")
	val state: String = EntityRelationState.LEGACY.name,
	@ColumnInfo(name = "updated_at", defaultValue = "0")
	val updatedAt: Long = 0L,
)
