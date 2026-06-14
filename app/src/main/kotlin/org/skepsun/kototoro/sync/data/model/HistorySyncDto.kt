package org.skepsun.kototoro.sync.data.model

import android.database.Cursor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.util.ext.buildContentValues

@Serializable
data class HistorySyncDto(
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("anchor_manga_id") val anchorMangaId: Long? = null,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("chapters") val chaptersCount: Int,
	@SerialName("manga") val manga: ContentSyncDto,
) {

	constructor(cursor: Cursor, manga: ContentSyncDto) : this(
		entityId = null,
		anchorMangaId = null,
		mangaId = cursor.getLong(cursor.getColumnIndexOrThrow("manga_id")),
		createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
		updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
		chapterId = cursor.getLong(cursor.getColumnIndexOrThrow("chapter_id")),
		page = cursor.getInt(cursor.getColumnIndexOrThrow("page")),
		scroll = cursor.getFloat(cursor.getColumnIndexOrThrow("scroll")),
		percent = cursor.getFloat(cursor.getColumnIndexOrThrow("percent")),
		deletedAt = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")),
		chaptersCount = cursor.getInt(cursor.getColumnIndexOrThrow("chapters")),
		manga = manga,
	)

	fun toContentValues() = buildContentValues(9) {
		put("manga_id", mangaId)
		put("created_at", createdAt)
		put("updated_at", updatedAt)
		put("chapter_id", chapterId)
		put("page", page)
		put("scroll", scroll)
		put("percent", percent)
		put("deleted_at", deletedAt)
		put("chapters", chaptersCount)
	}
}
