package org.skepsun.kototoro.tracker.data

import androidx.room.withTransaction
import org.skepsun.kototoro.core.db.MangaDatabase

fun mergeRestoredTrackNewChapters(local: TrackEntity, remote: TrackEntity): Int {
	val newer = if (remote.isNewerThan(local)) remote else local
	return if (newer.newChapters == 0) 0 else maxOf(local.newChapters, remote.newChapters)
}

fun TrackEntity.isNewerThan(other: TrackEntity): Boolean {
	return when {
		lastChapterDate != other.lastChapterDate -> lastChapterDate > other.lastChapterDate
		lastCheckTime != other.lastCheckTime -> lastCheckTime > other.lastCheckTime
		else -> lastChapterId > other.lastChapterId
	}
}

fun TrackEntity.canBeClearedBy(log: TrackLogEntity): Boolean {
	val trackTime = maxOf(lastChapterDate, lastCheckTime)
	return trackTime <= 0L || log.createdAt >= trackTime
}

suspend fun MangaDatabase.normalizeTrackFeedState() {
	withTransaction {
		getTrackLogsDao().repairWorkIdentities()
		getTrackLogsDao().deleteOrphans()
		getTrackLogsDao().ensureUnreadUpdateLogs()
		getTracksDao().insertTracksFromUnreadLogs()
		getTracksDao().restoreCountersFromUnreadLogs()
		getTracksDao().gc()
		getTrackLogsDao().gc()
		getTrackLogsDao().trim(TRACK_LOG_RETAINED_SIZE)
	}
}
