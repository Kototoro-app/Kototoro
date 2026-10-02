package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Per-content refresh bookkeeping for a strict source origin.
 *
 * A row is created when a refresh of a content item against its origin is attempted. The row is
 * only considered a success when the whole refresh — details plus chapters — has completed, which
 * is when [lastSuccessAt] is advanced. Indexes on both [sourceKey] and [contentId] back the
 * lookups performed by the source-recovery layer.
 */
@Entity(
    tableName = "source_refresh_state",
    primaryKeys = ["source_key", "content_id"],
)
data class SourceRefreshStateEntity(
    @ColumnInfo(name = "source_key", index = true)
    val sourceKey: String,
    @ColumnInfo(name = "content_id", index = true)
    val contentId: Long,
    /** Epoch millis of the last fully successful (details + chapters) refresh. */
    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long? = null,
    /** Epoch millis of the last refresh attempt, successful or not. */
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,
    /** Last error message, when the last attempt failed. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    /** Epoch millis of the last row update. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
