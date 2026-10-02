package org.skepsun.kototoro.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.entity.MangaEntity

const val TRACK_LOG_RETAINED_SIZE = 120

@Entity(
    tableName = "track_logs",
    indices = [
        Index(value = ["manga_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class TrackLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "chapters") val chapters: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "unread") val isUnread: Boolean,
) {
    val ownerId: Long get() = mangaId
    val entityId: Long? get() = null
}
