package org.skepsun.kototoro.scrobbling.common.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "scrobblings",
    primaryKeys = ["scrobbler", "id", "manga_id", "media_type"],
    indices = [
        Index(value = ["manga_id"]),
    ],
)
data class ScrobblingEntity(
    @ColumnInfo(name = "scrobbler") val scrobbler: Int,
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "target_id") val targetId: Long,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "chapter") val chapter: Int,
    @ColumnInfo(name = "comment") val comment: String?,
    @ColumnInfo(name = "rating") val rating: Float,
    @ColumnInfo(name = "media_type") val mediaType: String = "",
    @ColumnInfo(name = "remote_title") val remoteTitle: String? = null,
    @ColumnInfo(name = "remote_cover_url") val remoteCoverUrl: String? = null,
    @ColumnInfo(name = "remote_url") val remoteUrl: String? = null,
) {
    val ownerId: Long
        get() = mangaId

    val entityId: Long?
        get() = null
}

fun resolveScrobblingOwnerId(entityId: Long?, mangaId: Long): Long {
    return mangaId
}
