package org.skepsun.kototoro.tracker.data

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase

suspend fun MangaDatabase.normalizeTrackFeedState() {
    withTransaction {
        getTrackLogsDao().deleteOrphans()
        getTrackLogsDao().ensureUnreadUpdateLogs()
        getTracksDao().insertTracksFromUnreadLogs()
        getTracksDao().restoreCountersFromUnreadLogs()
        getTracksDao().gc()
        getTrackLogsDao().gc()
        getTrackLogsDao().trim(TRACK_LOG_RETAINED_SIZE)
    }
}
