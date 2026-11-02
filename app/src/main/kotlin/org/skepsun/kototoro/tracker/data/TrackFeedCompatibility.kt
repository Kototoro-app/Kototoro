package org.skepsun.kototoro.tracker.data

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase

suspend fun MangaDatabase.normalizeTrackFeedState() {
    withTransaction {
        getTrackLogsDao().deleteOrphans()
        getTrackLogsDao().ensureUnreadUpdateLogs()
        // Existing counters have already been merged by restore/sync. Historical events can overlap.
        getTracksDao().insertTracksFromUnreadLogs()
        getTracksDao().gc()
    }
}
