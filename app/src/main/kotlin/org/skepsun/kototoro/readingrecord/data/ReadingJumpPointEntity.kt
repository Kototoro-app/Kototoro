package org.skepsun.kototoro.readingrecord.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_READING_JUMP_POINTS
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Entity(
    tableName = TABLE_READING_JUMP_POINTS,
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("manga_id", "created_at"),
    ],
)
data class ReadingJumpPointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "from_chapter_id") val fromChapterId: Long,
    @ColumnInfo(name = "from_page") val fromPage: Int,
    @ColumnInfo(name = "from_scroll") val fromScroll: Int,
    @ColumnInfo(name = "from_percent") val fromPercent: Float,
    @ColumnInfo(name = "to_chapter_id") val toChapterId: Long,
    @ColumnInfo(name = "to_page") val toPage: Int,
    @ColumnInfo(name = "to_scroll") val toScroll: Int,
    @ColumnInfo(name = "to_percent") val toPercent: Float,
    @ColumnInfo(name = "source") val source: String,
)
