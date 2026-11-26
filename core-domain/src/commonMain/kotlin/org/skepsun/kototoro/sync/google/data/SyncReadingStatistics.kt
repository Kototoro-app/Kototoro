package org.skepsun.kototoro.sync.google.data

import kotlinx.coroutines.flow.toList
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.sync.google.data.model.SyncStats

/**
 * Reading time as Drive carries it: each duration row with the session that started with it, the way backups pair
 * them. Without the session a synced day had time but no chapter or position on the other device.
 */
suspend fun MangaDatabase.dumpSyncStats(): List<SyncStats> {
    val sessions = getReadingRecordDao().findOverlapping(0, Long.MAX_VALUE)
        .filter { it.endAt > it.startAt }
        .associateBy { it.mangaId to it.startAt }.toMutableMap()
    val rows = getStatsDao().dump().toList().map { SyncStats(it, sessions.remove(it.mangaId to it.startedAt)) }
    return rows + sessions.values.sortedBy { it.startAt }.map {
        SyncStats(StatsEntity(it.mangaId, it.startAt, it.endAt - it.startAt, 0), it)
    }
}

/** Merges by the row's identity, so a repeated sync never adds time twice or rewinds a session. */
suspend fun MangaDatabase.applySyncStats(remote: SyncStats, mangaId: Long) {
    if (remote.duration < 0 || remote.pages < 0) return
    val previous = getStatsDao().find(mangaId, remote.startedAt)
    getStatsDao().upsert(remote.toEntity(mangaId).copy(duration = maxOf(remote.duration, previous?.duration ?: 0),
        pages = maxOf(remote.pages, previous?.pages ?: 0)))
    val detail = remote.session ?: return
    // One malformed session from another device must not stop the rest of the sync.
    if (remote.startedAt < 0 || detail.endAt <= remote.startedAt || detail.startPage < 0 || detail.endPage < 0 ||
        detail.startScroll < 0 || detail.endScroll < 0) return
    val dao = getReadingRecordDao()
    val local = dao.findAtStart(mangaId, remote.startedAt)
    if (local != null && detail.endAt <= local.endAt) return
    val row = detail.toEntity(mangaId, remote.startedAt).copy(id = local?.id ?: 0,
        startPercent = detail.startPercent.orUnknownPercent(), endPercent = detail.endPercent.orUnknownPercent())
    if (local == null) dao.insertSession(row) else dao.updateSession(row)
}

// The Android writer allows special floats, so an unmeasured percentage can arrive as NaN; it means "unknown".
private fun Float.orUnknownPercent(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
