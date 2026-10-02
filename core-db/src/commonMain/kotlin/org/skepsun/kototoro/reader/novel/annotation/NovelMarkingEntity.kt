package org.skepsun.kototoro.reader.novel.annotation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A text marking stored against the processed novel chapter content. */
@Entity(
    tableName = "novel_markings",
    indices = [
        Index(value = ["manga_id"]),
        Index(value = ["manga_id", "chapter_id"]),
    ],
)
data class NovelMarkingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
    @ColumnInfo(name = "selected_text") val selectedText: String,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "color", defaultValue = "0") val color: Int = 0,
    @ColumnInfo(name = "style", defaultValue = "0") val style: Int = 0,
)
