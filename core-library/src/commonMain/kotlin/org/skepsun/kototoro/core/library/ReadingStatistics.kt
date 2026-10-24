package org.skepsun.kototoro.core.library

import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.stats.domain.ReadingDayStats
import org.skepsun.kototoro.stats.domain.StatsContentKind

/**
 * The rows one reader stop leaves behind, mirroring what Android writes from two places: [StatsEntity] is the
 * `StatsCollector` row (start, duration so far, pages turned) and [session] is the
 * `ReadingRecordRepository.recordSession` row that owns the time span.
 */
data class ReadingSessionWrite(
    val stats: StatsEntity,
    val session: ReadingRecordEntity? = null,
)

/**
 * Android counts reading time inside `ReaderViewModel`: `StatsCollector` keeps one open entry per work and upserts a
 * stats row on every state change, while a session row is written once the stop ends (and lasted long enough). The
 * Windows host has no view model holding that state, so the library tracks the open stop instead — the controller only
 * says when a reader opens and when it goes away, and every recorded page feeds the same two rows.
 *
 * A stop starts at the first page that reaches the history writer, exactly like Android, so the time before the first
 * page is not counted and a reader that never records anything leaves nothing behind. Rows are only tracked while the
 * host has opened a session, which keeps history writes from other screens (mark as read, batch operations) out of the
 * statistics.
 */
class ReadingSessionTracker(
    private val clock: () -> Long,
    private val minSessionDurationMs: Long = MIN_SESSION_DURATION_MS,
) {
    private var open: OpenSession? = null

    /** True while a reader stop is being counted; the host opens one when a reader screen appears. */
    val isTracking: Boolean get() = open != null

    /** Android opens the entry with the first state change; the work is only known once a page is recorded. */
    fun begin(now: Long = clock()) {
        open = OpenSession(startedAt = now)
    }

    /**
     * Feeds one recorded page. Returns the stats row to upsert, or null when no session is open or the page belongs to
     * a work other than the open stop (a stray write must never extend someone else's time).
     */
    fun onPage(contentId: Long, chapterId: Long, page: Int, percent: Float, now: Long = clock()): ReadingSessionWrite? {
        val session = open ?: return null
        val current = when {
            session.contentId == null -> session.copy(contentId = contentId, startChapterId = chapterId,
                startPage = page, startPercent = percent, lastChapterId = chapterId, lastPage = page,
                lastPercent = percent)
            session.contentId != contentId -> return null
            else -> {
                val turned = if (session.lastChapterId != chapterId || session.lastPage != page) 1 else 0
                session.copy(lastChapterId = chapterId, lastPage = page, lastPercent = percent,
                    pages = session.pages + turned)
            }
        }
        open = current
        val duration = (now - current.startedAt).coerceAtLeast(0)
        val stopped = requireNotNull(current.contentId)
        return ReadingSessionWrite(StatsEntity(stopped, current.startedAt, duration, current.pages))
    }

    /**
     * Android's `finishReadingSession`: the stop becomes a session row when it lasted at least
     * [minSessionDurationMs] long, next to the final stats row. Returns null when nothing was counted.
     */
    fun finish(now: Long = clock()): ReadingSessionWrite? {
        val session = open ?: return null
        open = null
        val contentId = session.contentId ?: return null
        val duration = (now - session.startedAt).coerceAtLeast(0)
        val stats = StatsEntity(contentId, session.startedAt, duration, session.pages)
        if (duration < minSessionDurationMs) return ReadingSessionWrite(stats)
        return ReadingSessionWrite(stats, ReadingRecordEntity(
            mangaId = contentId,
            startAt = session.startedAt,
            endAt = now,
            startChapterId = session.startChapterId,
            startPage = session.startPage,
            startScroll = 0,
            endChapterId = session.lastChapterId,
            endPage = session.lastPage,
            endScroll = 0,
            startPercent = session.startPercent,
            endPercent = session.lastPercent,
        ))
    }

    /** Drops an open stop without writing it, for a reader the host decided not to record. */
    fun abandon() {
        open = null
    }

    private data class OpenSession(
        val contentId: Long? = null,
        val startedAt: Long,
        val startChapterId: Long = 0L,
        val startPage: Int = 0,
        val startPercent: Float = 0f,
        val lastChapterId: Long = 0L,
        val lastPage: Int = 0,
        val lastPercent: Float = 0f,
        val pages: Int = 0,
    )

    private companion object {
        /** Android's `ReadingRecordRepository.MIN_SESSION_DURATION_MS`. */
        const val MIN_SESSION_DURATION_MS = 5_000L
    }
}

/** One work of the dashboard, carrying the stored content so the host can show its cover and open its details. */
data class LibraryReadingStatsWork(
    val content: SourceContent,
    val kind: StatsContentKind,
    val duration: Long,
    val sessions: Int,
    val units: Int,
    val lastActivityAt: Long,
)

/** Android's `StatsDashboard` over the shared derivation, with the stored works instead of parser contents. */
data class LibraryReadingStats(
    val works: List<LibraryReadingStatsWork> = emptyList(),
    val dailyActivity: List<ReadingDayStats> = emptyList(),
    val hourlyActivity: List<Long> = List(24) { 0L },
    val totalDuration: Long = 0L,
    val activeDays: Int = 0,
    val currentStreak: Int = 0,
    val sessionCount: Int = 0,
)
