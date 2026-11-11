package org.skepsun.kototoro.tracker.data

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource

suspend fun MangaDatabase.normalizeTrackFeedState() {
    withTransaction {
        normalizeRestoredTrackFeedState()
    }
}

/** See [TracksDao.clearUnrecordedCounters]; local copies keep theirs. */
suspend fun MangaDatabase.clearUnrecordedTrackCounters(): Int = getTracksDao().clearUnrecordedCounters(
    listOf(LocalMangaSource.name, LocalNovelSource.name, LocalVideoSource.name),
)
