package org.skepsun.kototoro.entitygraph.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION

@Entity(
	tableName = TABLE_ENTITY_GRAPH_ENTITY,
	indices = [
		Index(name = "idx_entity_name", value = ["primary_name"]),
	],
)
data class EntityRecord(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "type") val type: String,
	@ColumnInfo(name = "primary_name") val primaryName: String,
	@ColumnInfo(name = "aliases") val aliases: String?,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "last_accessed") val lastAccessed: Long,
	@ColumnInfo(name = "access_count") val accessCount: Int,
)

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
)

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
		Index(name = "idx_relation_unique", value = ["from_entity_id", "to_entity_id", "type"], unique = true),
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
)
