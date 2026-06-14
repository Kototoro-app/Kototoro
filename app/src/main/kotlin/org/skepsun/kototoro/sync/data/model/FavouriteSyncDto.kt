package org.skepsun.kototoro.sync.data.model

import android.database.Cursor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.util.ext.buildContentValues
import org.skepsun.kototoro.core.util.ext.getBoolean

@Serializable
data class FavouriteSyncDto(
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("manga") val manga: ContentSyncDto,
	@SerialName("category_id") val categoryId: Int,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val pinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") var deletedAt: Long,
	@SerialName("updated_at") val updatedAt: Long = 0L,
) {

	constructor(cursor: Cursor, manga: ContentSyncDto) : this(
		entityId = null,
		mangaId = cursor.getLong(cursor.getColumnIndexOrThrow("manga_id")),
		manga = manga,
		categoryId = cursor.getInt(cursor.getColumnIndexOrThrow("category_id")),
		sortKey = cursor.getInt(cursor.getColumnIndexOrThrow("sort_key")),
		pinned = cursor.getBoolean(cursor.getColumnIndexOrThrow("pinned")),
		createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
		deletedAt = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")),
		updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
	)

	fun toContentValues() = buildContentValues(7) {
		put("manga_id", mangaId)
		put("category_id", categoryId)
		put("sort_key", sortKey)
		put("pinned", pinned)
		put("created_at", createdAt)
		put("deleted_at", deletedAt)
		put("updated_at", updatedAt)
	}
}
