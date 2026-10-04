package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.favourites.data.FavouriteEntity

@Serializable
class WorkFavouriteBackup(
    @SerialName("entity_id") val entityId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("anchor_manga_id") val anchorMangaId: Long? = null,
    @SerialName("sort_key") val sortKey: Int = 0,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toFavouriteEntity(targetMangaId: Long = anchorMangaId ?: 0L, targetCategoryId: Long = categoryId) = FavouriteEntity(
        mangaId = targetMangaId,
        categoryId = targetCategoryId,
        sortKey = sortKey,
        isPinned = isPinned,
        createdAt = createdAt,
        deletedAt = deletedAt,
        updatedAt = maxOf(updatedAt, createdAt),
    )
}
