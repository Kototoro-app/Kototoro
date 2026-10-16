package org.skepsun.kototoro.ios

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.stats.domain.*
import kotlin.time.Clock
import kotlin.time.Instant

class IosStatsDay internal constructor(value: ReadingDayStats) {
    val date = value.date
    val startedAt = value.startedAt
    val duration = value.duration
}

data class IosStatsHour(val hour: Int, val duration: Long)

class IosStatsWork internal constructor(
    val content: IosSourceContent,
    value: ReadingWorkStats?,
    val updatedAt: Long,
    val chapterTitle: String?,
    progress: Float?,
    fallbackDay: ReadingDayStats?,
    val isHistoryVisible: Boolean,
) {
    val duration = value?.duration ?: 0L
    val sessions = value?.sessions ?: 0
    val units = value?.units ?: 0
    val activeDays = value?.activeDays ?: 0
    val firstActivityAt = value?.firstActivityAt ?: updatedAt
    val hasTimedStats = value != null
    val hasProgress = progress != null
    val progress = progress ?: 0f
    val dailyActivity = (value?.dailyActivity ?: listOfNotNull(fallbackDay)).map(::IosStatsDay)
}

data class IosTimelineEvent(
    val id: String,
    val content: IosSourceContent,
    val timestamp: Long,
    val isUpdate: Boolean,
    val detail: String,
    val duration: Long,
    val count: Int,
)

class IosStatsDashboard internal constructor(
    value: ReadingStatistics,
    val works: List<IosStatsWork>,
    val timeline: List<IosTimelineEvent>,
) {
    val totalDuration = value.totalDuration
    val activeDays = value.activeDays
    val currentStreak = value.currentStreak
    val sessionCount = value.sessionCount
    val workCount = value.works.size
    val dailyActivity = value.dailyActivity.map(::IosStatsDay)
    val hourlyActivity = value.hourlyActivity.mapIndexed { hour, duration -> IosStatsHour(hour, duration) }
}

/** A narrow Swift-facing application API over the shared tables and Android/iOS statistics rules. */
class IosStatistics internal constructor(private val owner: IosLibrary) {
    @Throws(Exception::class)
    suspend fun snapshot(period: String, kind: String, query: String, contentId: String?): IosStatsDashboard {
        val now = Clock.System.now().toEpochMilliseconds()
        val zone = TimeZone.currentSystemDefault()
        val window = ReadingStatsWindow(ReadingStatsPeriod.valueOf(period), now, zone)
        return owner.database.useReaderConnection { connection -> connection.deferredTransaction {
            val db = owner.database
            val id = contentId?.toLong()
            val sessions = if (id == null) db.getReadingRecordDao().findOverlapping(window.from, now)
                else db.getReadingRecordDao().findSessions(id)
            val legacy = if (id == null) db.getStatsDao().findOverlapping(window.from, now)
                else db.getStatsDao().findAll(id)
            val allHistory = if (id == null) db.getHistoryDao().findAllEntriesIncludingDeleted()
                else listOfNotNull(db.getHistoryDao().findIncludingDeleted(id))
            val deletedIds = allHistory.filter { it.deletedAt != 0L }.map { it.mangaId }.toSet()
            val history = allHistory
                .filter { it.deletedAt == 0L && (contentId == null || it.mangaId.toString() == contentId) }
                .associateBy { it.mangaId }
            val logs = if (contentId == null) db.getTrackLogsDao().dump()
                .filter { it.createdAt >= window.from && it.createdAt <= now } else emptyList()
            val ids = (sessions.map { it.mangaId } + legacy.map { it.mangaId } + history.keys + logs.map { it.mangaId })
                .toSet()
            val metadata = owner.tracking.selected(ids.toList())
            val text = query.trim()
            val contents = owner.records.findMany(ids).map { IosSourceContent(it, metadata[it.id]) }
                .filter { (kind == "ALL" || it.mediaKind == kind) &&
                    (text.isEmpty() || it.title.contains(text, ignoreCase = true) ||
                        it.authors.any { author -> author.contains(text, ignoreCase = true) }) }
                .associateBy { it.value.id }
            val value = deriveReadingStatistics(sessions, legacy,
                contents.mapValues { StatsContentKind.fromType(it.value.contentType) }, window)
            val statsById = value.works.associateBy { it.contentId }
            val sessionsById = sessions.groupBy { it.mangaId }
            val legacyById = legacy.groupBy { it.mangaId }
            val chapterIds = sessions.map { it.endChapterId } + history.values.map { it.chapterId }
            val chapters = chapterIds.distinct().chunked(200).flatMap { db.getChaptersDao().findByIds(it) }
                .associateBy { it.mangaId to it.chapterId }
            val works = contents.mapNotNull { (id, content) ->
                val stats = statsById[id]
                val recent = history[id]
                if (stats == null && (recent == null || recent.updatedAt < window.from)) return@mapNotNull null
                val latest = sessionsById[id].orEmpty().maxByOrNull { it.endAt }
                val lastAt = maxOf(stats?.lastActivityAt ?: 0, recent?.updatedAt ?: 0)
                val chapterId = latest?.endChapterId ?: recent?.chapterId
                val title = chapters[id to chapterId]?.title?.takeIf(String::isNotBlank)
                val localDay = Instant.fromEpochMilliseconds(lastAt).toLocalDateTime(zone).date
                IosStatsWork(content, stats, lastAt, title, latest?.endPercent ?: recent?.percent,
                    ReadingDayStats(localDay.toString(), localDay.atStartOfDayIn(zone).toEpochMilliseconds(), 0),
                    id !in deletedIds)
            }.sortedByDescending { it.updatedAt }
            val timeline = ArrayList<IosTimelineEvent>()
            for (work in works) {
                // One work per active day keeps the two timeline lanes compact, without discarding prior days.
                for (day in work.dailyActivity) {
                    val dayEnd = LocalDate.parse(day.date).plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                    val dailySessions = sessionsById[work.content.value.id].orEmpty().filter {
                        it.startAt < dayEnd && it.endAt > day.startedAt
                    }
                    val latest = dailySessions.maxByOrNull { it.endAt }
                    val legacyEnd = legacyById[work.content.value.id].orEmpty().filter {
                        it.startedAt < dayEnd && it.startedAt + it.duration > day.startedAt
                    }.maxOfOrNull { it.startedAt + it.duration }
                    val timestamp = minOf(now, dayEnd, latest?.endAt ?: legacyEnd ?: (day.startedAt + 1)) - 1
                    val detail = latest?.let { chapters[it.mangaId to it.endChapterId]?.title }
                        ?: if (!work.hasTimedStats) work.chapterTitle else "旧版阅读统计"
                    timeline += IosTimelineEvent("read-${work.content.id}-${day.date}", work.content,
                        if (!work.hasTimedStats) work.updatedAt else timestamp, false,
                        detail?.takeIf(String::isNotBlank) ?: "阅读记录", day.duration, dailySessions.size)
                }
            }
            logs.forEach { log -> contents[log.mangaId]?.let { content ->
                val names = log.chapters.lines().filter(String::isNotBlank)
                timeline += IosTimelineEvent("update-${log.id}", content, log.createdAt, true,
                    names.joinToString("、"), 0, names.size)
            } }
            IosStatsDashboard(value, works, timeline.sortedByDescending { it.timestamp })
        } }
    }

    /** Repeated checkpoints update the same row; a retry after suspension cannot duplicate a session. */
    @Throws(Exception::class)
    suspend fun record(content: IosSourceContent, sample: IosReadingSample) {
        val value = sample.value
        if (value.endedAt - value.startedAt < 5_000L) return
        val db = owner.database
        db.useWriterConnection { connection -> connection.immediateTransaction {
            val stored = requireNotNull(db.getMangaDao().find(content.value.id)).manga
            require(stored.source == content.sourceName && stored.url == content.url) { "Reading work changed" }
            val chapters = db.getChaptersDao().findByIds(listOf(value.start.chapterId, value.end.chapterId))
            require(chapters.any { it.mangaId == stored.id && it.chapterId == value.start.chapterId } &&
                chapters.any { it.mangaId == stored.id && it.chapterId == value.end.chapterId })
            val dao = db.getReadingRecordDao()
            val previous = dao.findAtStart(stored.id, value.startedAt)
            if (previous != null && previous.endAt >= value.endedAt) return@immediateTransaction
            val entity = ReadingRecordEntity(id = previous?.id ?: 0, mangaId = stored.id,
                startAt = value.startedAt, endAt = value.endedAt,
                startChapterId = value.start.chapterId, startPage = value.start.page, startScroll = value.start.scroll,
                endChapterId = value.end.chapterId, endPage = value.end.page, endScroll = value.end.scroll,
                startPercent = value.start.percent, endPercent = value.end.percent)
            if (previous == null) dao.insertSession(entity) else dao.updateSession(entity)
            // Keep duration compatible with existing Android backups. The shared deriver removes its overlap.
            db.getStatsDao().upsert(StatsEntity(stored.id, value.startedAt, value.endedAt - value.startedAt,
                if (StatsContentKind.fromType(content.contentType) == StatsContentKind.MANGA) value.pages else 0))
        } }
    }
}

class IosReadingSample internal constructor(internal val value: ReadingSessionSample) {
    val key: String get() = value.startedAt.toString()
}

/** Synchronous state belongs to the Swift main actor; samples crossing the database boundary are immutable. */
class IosReadingTracker(content: IosSourceContent) {
    private val tracker = ReadingSessionTracker()
    private val chapterProgress = content.value.chapters.orEmpty().groupBy { it.branch }.values.flatMap { branch ->
        branch.mapIndexed { index, chapter -> chapter.id to (index to branch.size) }
    }.toMap()

    fun update(chapter: IosSourceChapter, index: Int, count: Int, lastVisible: Int, scroll: Float,
        active: Boolean): IosReadingSample? {
        require(count > 0 && index in 0 until count && lastVisible in index until count && scroll.isFinite())
        val (chapterIndex, chapterCount) = requireNotNull(chapterProgress[chapter.value.id])
        val position = ReadingSessionPosition(chapter.value.id, index, scroll.coerceIn(0f, Int.MAX_VALUE.toFloat()).toInt(),
            (chapterIndex + (lastVisible + 1f) / count) / chapterCount)
        return tracker.update(position, Clock.System.now().toEpochMilliseconds(), active, lastVisible)?.let(::IosReadingSample)
    }

    fun checkpoint(): IosReadingSample? = tracker.snapshot(Clock.System.now().toEpochMilliseconds())?.let(::IosReadingSample)
    fun pause(): IosReadingSample? = tracker.finish(Clock.System.now().toEpochMilliseconds())?.let(::IosReadingSample)
}
