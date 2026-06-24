package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity

@Serializable
class WorkFavouriteBackup(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("anchor_manga_id") val anchorMangaId: Long? = null,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val isPinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
) {

	constructor(entity: WorkFavouriteEntity) : this(
		entityId = entity.entityId,
		categoryId = entity.categoryId,
		anchorMangaId = entity.anchorMangaId,
		sortKey = entity.sortKey,
		isPinned = entity.isPinned,
		createdAt = entity.createdAt,
		deletedAt = entity.deletedAt,
		updatedAt = entity.updatedAt,
	)

	fun toEntity() = WorkFavouriteEntity(
		entityId = entityId,
		categoryId = categoryId,
		anchorMangaId = anchorMangaId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = deletedAt,
		updatedAt = updatedAt,
	)
}
