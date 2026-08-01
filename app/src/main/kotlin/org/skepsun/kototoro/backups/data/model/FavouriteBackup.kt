package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity

@Serializable
class FavouriteBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int = 0,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
	@SerialName("updated_at") val updatedAt: Long = 0L,
	@SerialName("manga") val manga: ContentBackup,
) {
	// Legacy favourites payload keeps a projection snapshot only.
	// Work/entity favourites state is exported via dedicated work sections.

	constructor(entity: FavouriteContent) : this(
		mangaId = entity.manga.id,
		categoryId = entity.favourite.categoryId,
		sortKey = entity.favourite.sortKey,
		isPinned = entity.favourite.isPinned,
		createdAt = entity.favourite.createdAt,
		deletedAt = entity.favourite.deletedAt,
		updatedAt = entity.favourite.updatedAt,
		manga = ContentBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	constructor(entity: WorkFavouriteEntity, manga: MangaWithTags) : this(
		mangaId = manga.manga.id,
		categoryId = entity.categoryId,
		sortKey = entity.sortKey,
		isPinned = entity.isPinned,
		createdAt = entity.createdAt,
		deletedAt = entity.deletedAt,
		updatedAt = entity.updatedAt,
		manga = ContentBackup(manga),
	)

	fun toEntity() = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = deletedAt,
		updatedAt = maxOf(updatedAt, createdAt),
	)
}
