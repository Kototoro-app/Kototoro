package org.skepsun.kototoro.tracker.data

import androidx.room.ColumnInfo

/** A track in check order, with the source its title comes from (null when the title row is gone). */
data class TrackQueueEntry(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String?,
)
