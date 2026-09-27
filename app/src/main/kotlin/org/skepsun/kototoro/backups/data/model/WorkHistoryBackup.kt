package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.history.data.HistoryEntity

@Serializable
class WorkHistoryBackup(
    @SerialName("entity_id") val entityId: Long,
    @SerialName("anchor_manga_id") val anchorMangaId: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("page") val page: Int,
    @SerialName("scroll") val scroll: Float,
    @SerialName("percent") val percent: Float,
    @SerialName("deleted_at") val deletedAt: Long = 0L,
    @SerialName("chapters") val chaptersCount: Int,
    @SerialName("parent_chapter_id") val parentChapterId: Long? = null,
) {
    fun toHistoryEntity(targetMangaId: Long = anchorMangaId) = HistoryEntity(
        mangaId = targetMangaId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chapterId = chapterId,
        page = page,
        scroll = scroll,
        percent = percent,
        deletedAt = deletedAt,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )
}
