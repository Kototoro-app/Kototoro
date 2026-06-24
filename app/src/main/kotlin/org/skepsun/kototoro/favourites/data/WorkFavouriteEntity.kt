package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.entitygraph.data.EntityRecord

@Entity(
	tableName = TABLE_WORK_FAVOURITES,
	primaryKeys = ["entity_id", "category_id"],
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = FavouriteCategoryEntity::class,
			parentColumns = ["category_id"],
			childColumns = ["category_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(name = "idx_work_favourites_entity", value = ["entity_id"]),
		Index(name = "idx_work_favourites_category", value = ["category_id"]),
		Index(name = "idx_work_favourites_anchor_manga", value = ["anchor_manga_id"]),
	],
)
data class WorkFavouriteEntity(
	@ColumnInfo(name = "entity_id") val entityId: Long,
	@ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "anchor_manga_id") val anchorMangaId: Long?,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
