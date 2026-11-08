package org.skepsun.kototoro.tracker.data

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource

suspend fun MangaDatabase.normalizeTrackFeedState() {
    withTransaction {
        getTrackLogsDao().deleteOrphans()
        // Counters that no update record backs are left over, so they are cleared rather than given a placeholder
        // record without chapters.
        clearUnrecordedTrackCounters()
        // Existing counters have already been merged by restore/sync. Historical events can overlap.
        getTracksDao().insertTracksFromUnreadLogs()
        getTracksDao().gc()
    }
}

/** See [TracksDao.clearUnrecordedCounters]; local copies keep theirs. */
suspend fun MangaDatabase.clearUnrecordedTrackCounters(): Int = getTracksDao().clearUnrecordedCounters(
    listOf(LocalMangaSource.name, LocalNovelSource.name, LocalVideoSource.name),
)
