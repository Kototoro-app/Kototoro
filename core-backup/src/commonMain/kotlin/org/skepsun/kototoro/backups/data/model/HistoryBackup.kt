package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.HistoryWithContent

@Serializable
class HistoryBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("page") val page: Int,
    @SerialName("scroll") val scroll: Float,
    @SerialName("percent") val percent: Float = BACKUP_PROGRESS_NONE,
    @SerialName("chapters") val chaptersCount: Int = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
    @SerialName("manga") val manga: ContentBackup,
) {
    // History is owned by the manga itself.
    // Legacy backups may still carry a separate work history section; it is
    // translated into this shape on restore by [BackupRepository].

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
