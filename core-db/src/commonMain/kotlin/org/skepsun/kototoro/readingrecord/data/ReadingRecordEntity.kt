package org.skepsun.kototoro.readingrecord.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_READING_SESSIONS
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Entity(
    tableName = TABLE_READING_SESSIONS,
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("manga_id", "start_at"),
        Index("manga_id", "end_at"),
    ],
)
data class ReadingRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    @ColumnInfo(name = "start_chapter_id") val startChapterId: Long,
    @ColumnInfo(name = "start_page") val startPage: Int,
    @ColumnInfo(name = "start_scroll") val startScroll: Int,
    @ColumnInfo(name = "end_chapter_id") val endChapterId: Long,
    @ColumnInfo(name = "end_page") val endPage: Int,
    @ColumnInfo(name = "end_scroll") val endScroll: Int,
    @ColumnInfo(name = "start_percent") val startPercent: Float,
    @ColumnInfo(name = "end_percent") val endPercent: Float,
)
