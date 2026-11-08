package org.skepsun.kototoro.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.entity.MangaEntity

/**
 * When a tracked work is due for its next check. Kept apart from [TrackEntity]: it is this device's scheduling
 * state, not part of a backup or a sync, and many writers rebuild a track row without knowing about it.
 */
@Entity(
    tableName = "track_schedule",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackScheduleEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    /** Days between checks, from the work's release pace. */
    @ColumnInfo(name = "fetch_interval") val fetchInterval: Int,
    /** Epoch millis from which the work is due; a failing work is pushed back by [failCount]. */
    @ColumnInfo(name = "next_check_time") val nextCheckTime: Long,
    /** Checks failed in a row. */
    @ColumnInfo(name = "fail_count") val failCount: Int,
)
