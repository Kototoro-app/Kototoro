package org.skepsun.kototoro.backups.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.skepsun.kototoro.backups.data.model.StatisticBackup
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.stats.data.StatsEntity

/** Called inside the platform's backup transaction; keeps the existing statistics section and legacy counters. */
fun MangaDatabase.dumpReadingStatistics(): Flow<StatisticBackup> = flow {
    val sessions = getReadingRecordDao().findOverlapping(0, Long.MAX_VALUE)
        .filter { it.endAt > it.startAt }
        .associateBy { it.mangaId to it.startAt }.toMutableMap()
    getStatsDao().dump().collect { row -> emit(StatisticBackup(row, sessions.remove(row.mangaId to row.startedAt))) }
    for (session in sessions.values.sortedBy { it.startAt }) {
        emit(StatisticBackup(StatsEntity(session.mangaId, session.startAt, session.endAt - session.startAt, 0), session))
    }
}

/** Merge counters and checkpoints by their original identity; retries never add duplicate time or sessions. */
suspend fun MangaDatabase.restoreReadingStatistic(backup: StatisticBackup) {
    require(backup.mangaId in getMangaDao()) { "备份阅读统计缺少对应作品" }
    require(backup.duration >= 0 && backup.pages >= 0) { "备份阅读统计无效" }
    backup.session?.let { detail ->
        require(backup.startedAt >= 0 && detail.endAt > backup.startedAt &&
            detail.endAt - backup.startedAt <= backup.duration &&
            detail.startPage >= 0 && detail.endPage >= 0 && detail.startScroll >= 0 && detail.endScroll >= 0) {
            "备份阅读会话无效"
        }
    }
    val previous = getStatsDao().find(backup.mangaId, backup.startedAt)
    getStatsDao().upsert(backup.toEntity().copy(duration = maxOf(backup.duration, previous?.duration ?: 0),
        pages = maxOf(backup.pages, previous?.pages ?: 0)))
    backup.session?.let { detail ->
        val dao = getReadingRecordDao()
        val local = dao.findAtStart(backup.mangaId, backup.startedAt)
        if (local == null || detail.endAt > local.endAt) {
            // The Android writer allows special floats, so an unmeasured percentage can arrive as NaN/Infinity; that
            // means "unknown", not "unreadable session".
            val row = detail.toEntity(backup.mangaId, backup.startedAt).copy(id = local?.id ?: 0,
                startPercent = detail.startPercent.orUnknownPercent().coerceIn(0f, 1f),
                endPercent = detail.endPercent.orUnknownPercent().coerceIn(0f, 1f))
            if (local == null) dao.insertSession(row) else dao.updateSession(row)
        }
    }
}

private fun Float.orUnknownPercent(): Float = if (isFinite()) this else 0f
