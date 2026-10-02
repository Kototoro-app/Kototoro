package org.skepsun.kototoro.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists visual/timestamp annotations for manga (panel crop) and video (timestamp frame).
 */
@Entity(
    tableName = "media_notes",
    indices = [
        Index(value = ["manga_id"]),
        Index(value = ["manga_id", "chapter_id"]),
    ],
)
data class MediaNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "media_type") val mediaType: Int, // 0: MANGA_CROP, 1: VIDEO_TIMESTAMP
    @ColumnInfo(name = "page", defaultValue = "0") val page: Int = 0,
    @ColumnInfo(name = "position_ms", defaultValue = "0") val positionMs: Long = 0L,
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "quote_text") val quoteText: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "crop_left", defaultValue = "0.0") val cropLeft: Float = 0f,
    @ColumnInfo(name = "crop_top", defaultValue = "0.0") val cropTop: Float = 0f,
    @ColumnInfo(name = "crop_right", defaultValue = "1.0") val cropRight: Float = 1f,
    @ColumnInfo(name = "crop_bottom", defaultValue = "1.0") val cropBottom: Float = 1f,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val MEDIA_TYPE_MANGA_CROP = 0
        const val MEDIA_TYPE_VIDEO_TIMESTAMP = 1
    }
}
