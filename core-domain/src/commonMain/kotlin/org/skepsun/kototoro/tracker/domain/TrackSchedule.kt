package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.tracker.data.TrackScheduleEntity

/*
 * When a tracked work is next checked, after Mihon's FetchInterval: works are checked at the pace they release,
 * a work that keeps failing is pushed back, and every work is still checked at least weekly. Background checks pick
 * only due works; a check the user asks for checks everything.
 */

const val DEFAULT_FETCH_INTERVAL_DAYS = 7
const val MAX_FETCH_INTERVAL_DAYS = 28

/** However slow a work releases, it is checked at least this often. */
const val MAX_DAYS_BETWEEN_CHECKS = 7

/** A failing work waits 1, 2, 4, then this many days between checks. */
const val MAX_FAILURE_BACKOFF_DAYS = 7

/** Works that failed this many checks in a row are listed as persistently failing. */
const val PERSISTENT_FAILURE_COUNT = 5

private const val DAY_MILLIS = 86_400_000L

/** A work due within this window counts as due now; background runs are hours apart. */
private const val DUE_GRACE_MILLIS = DAY_MILLIS

/**
 * Days between a work's releases, as Mihon computes them: the median gap between the most recent distinct release
 * days (the last 3 for a short list, else the last 10), from upload dates when at least three are known, else from
 * the days new chapters were discovered, else [DEFAULT_FETCH_INTERVAL_DAYS]; between 1 and [MAX_FETCH_INTERVAL_DAYS].
 * Days are UTC days.
 */
fun fetchIntervalDays(uploadDates: List<Long>, discoveryDates: List<Long>, chapterCount: Int): Int {
    val window = if (chapterCount <= 8) 3 else 10
    fun recentDays(dates: List<Long>) = dates.asSequence()
        .filter { it > 0L }
        .map { it.floorDiv(DAY_MILLIS) }
        .distinct()
        .sortedDescending()
        .take(window)
        .toList()

    fun medianGap(days: List<Long>): Int {
        val gaps = days.zipWithNext { newer, older -> newer - older }.sorted()
        return gaps[(gaps.size - 1) / 2].toInt()
    }
    val uploads = recentDays(uploadDates)
    val discoveries = recentDays(discoveryDates)
    val interval = when {
        uploads.size >= 3 -> medianGap(uploads)
        discoveries.size >= 3 -> medianGap(discoveries)
        else -> DEFAULT_FETCH_INTERVAL_DAYS
    }
    return interval.coerceIn(1, MAX_FETCH_INTERVAL_DAYS)
}

/**
 * When a work whose latest release was at [latestRelease] (epoch millis, 0 when unknown) is next due after a
 * successful check at [now]: the next multiple of [intervalDays] after the release day, the interval doubling (as
 * in Mihon, up to [MAX_FETCH_INTERVAL_DAYS]) for every ten cycles in a row that brought nothing. Never later than
 * [MAX_DAYS_BETWEEN_CHECKS] from [now].
 */
fun nextCheckAfterSuccess(latestRelease: Long, intervalDays: Int, now: Long): Long {
    val latestDay = (if (latestRelease > 0L) latestRelease else now).floorDiv(DAY_MILLIS)
    val daysSince = (now.floorDiv(DAY_MILLIS) - latestDay).coerceAtLeast(0L)
    var interval = intervalDays.coerceIn(1, MAX_FETCH_INTERVAL_DAYS).toLong()
    while (interval < MAX_FETCH_INTERVAL_DAYS && daysSince / interval + 1 > 10) {
        interval = (interval * 2).coerceAtMost(MAX_FETCH_INTERVAL_DAYS.toLong())
    }
    val next = (latestDay + (daysSince / interval + 1) * interval) * DAY_MILLIS
    return next.coerceAtMost(now + MAX_DAYS_BETWEEN_CHECKS * DAY_MILLIS)
}

/** When a work that failed [failCount] checks in a row (at least one) is next due after failing at [now]. */
fun nextCheckAfterFailure(failCount: Int, now: Long): Long {
    val days = (1L shl (failCount - 1).coerceIn(0, 3)).coerceAtMost(MAX_FAILURE_BACKOFF_DAYS.toLong())
    return now + days * DAY_MILLIS
}

/** Whether a work next due at [nextCheckTime] (0 when it has no schedule yet) is due for a background check. */
fun isTrackDue(nextCheckTime: Long, now: Long): Boolean = nextCheckTime <= now + DUE_GRACE_MILLIS

/**
 * Records the outcome of a check of work [mangaId] in its schedule: after a success the interval is recomputed from
 * its stored chapters and recorded updates and the failure count resets; after a failure the work is pushed back.
 * Runs inside the caller's write transaction.
 */
suspend fun MangaDatabase.updateTrackSchedule(mangaId: Long, succeeded: Boolean, now: Long) {
    val dao = getTrackScheduleDao()
    val previous = dao.find(mangaId)
    if (!succeeded) {
        val failures = (previous?.failCount ?: 0) + 1
        dao.upsert(TrackScheduleEntity(
            mangaId = mangaId,
            fetchInterval = previous?.fetchInterval ?: DEFAULT_FETCH_INTERVAL_DAYS,
            nextCheckTime = nextCheckAfterFailure(failures, now),
            failCount = failures,
        ))
        return
    }
    val uploads = getChaptersDao().findAll(mangaId).map { it.uploadDate }
    val logs = getTrackLogsDao().findByOwner(mangaId)
    val discoveries = logs.flatMap { log ->
        decodeUpdateChapters(log.chapterMetadata).map { it.discoveredAt }.ifEmpty { listOf(log.createdAt) }
    }
    val interval = fetchIntervalDays(uploads, discoveries, uploads.size)
    val latestRelease = maxOf(uploads.maxOrNull() ?: 0L, discoveries.maxOrNull() ?: 0L).coerceAtMost(now)
    dao.upsert(TrackScheduleEntity(
        mangaId = mangaId,
        fetchInterval = interval,
        nextCheckTime = nextCheckAfterSuccess(latestRelease, interval, now),
        failCount = 0,
    ))
}
