package org.skepsun.kototoro.history.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Entity(
    tableName = TABLE_HISTORY,
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "scroll") val scroll: Float,
    @ColumnInfo(name = "percent") val percent: Float,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "chapters") val chaptersCount: Int,
    // EPUB父章节ID，用于支持EPUB内部章节的历史记录
    // 对于EPUB内部章节：chapterId是内部章节ID，parentChapterId是父章节ID
    // 对于普通章节：parentChapterId为null或等于chapterId
    @ColumnInfo(name = "parent_chapter_id") val parentChapterId: Long? = null,
)
