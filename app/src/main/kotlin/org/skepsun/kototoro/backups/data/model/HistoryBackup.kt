package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.HistoryWithContent
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE

@Serializable
class HistoryBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float = PROGRESS_NONE,
	@SerialName("chapters") val chaptersCount: Int = 0,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
	@SerialName("manga") val manga: ContentBackup,
) {
	// Legacy history payload keeps a projection snapshot only.
	// Work-level history state is exported separately and reconstructed independently.

	constructor(entity: HistoryWithContent) : this(
		mangaId = entity.manga.id,
		createdAt = entity.history.createdAt,
		updatedAt = entity.history.updatedAt,
		chapterId = entity.history.chapterId,
		page = entity.history.page,
		scroll = entity.history.scroll,
		percent = entity.history.percent,
		chaptersCount = entity.history.chaptersCount,
		deletedAt = entity.history.deletedAt,
		manga = ContentBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	constructor(entity: WorkHistoryEntity, manga: MangaWithTags) : this(
		mangaId = manga.manga.id,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		chapterId = entity.chapterId,
		page = entity.page,
		scroll = entity.scroll,
		percent = entity.percent,
		chaptersCount = entity.chaptersCount,
		deletedAt = entity.deletedAt,
		manga = ContentBackup(manga),
	)

	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		deletedAt = deletedAt,
		chaptersCount = chaptersCount,
	)
}
