package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epub_chapters")
data class EpubChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo(name = "novel_id")
    val novelId: String,
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String,
    @ColumnInfo(name = "chapter_url")
    val chapterUrl: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long,
)
