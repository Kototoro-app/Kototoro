package org.skepsun.kototoro.readingrecord.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.ReadingStatsWindow
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.deriveReadingStatistics
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.ReaderState
import javax.inject.Inject

data class ReadingRecordSummary(
    val totalDuration: Long = 0L,
    val readingDays: Int = 0,
    val lastReadAt: Long? = null,
)

data class ReadingRecordSnapshot(
    val summary: ReadingRecordSummary = ReadingRecordSummary(),
    val sessions: List<ReadingRecordEntity> = emptyList(),
    val chapters: List<ReadingChapterAggregateEntity> = emptyList(),
    val jumpPoints: List<ReadingJumpPointEntity> = emptyList(),
)

class ReadingRecordRepository @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val mangaRepository: ContentDataRepository,
) {

    fun observeSnapshot(mangaId: Long): Flow<ReadingRecordSnapshot> {
        val anchorIds = listOf(mangaId)
        val dao = db.getReadingRecordDao()
        val readings = combine(
            dao.observeSessions(anchorIds),
            db.getStatsDao().observeRowCount(mangaId),
        ) { sessions, _ -> sessions to db.getStatsDao().findAll(mangaId) }
        return combine(
            readings,
            dao.observeChapterAggregates(anchorIds),
            dao.observeJumpPoints(anchorIds, DEFAULT_JUMP_LIMIT),
        ) { (sessions, legacy), chapters, jumpPoints ->
            val stored = db.getMangaDao().find(mangaId)?.manga
            val value = deriveReadingStatistics(sessions, legacy,
                mapOf(mangaId to StatsContentKind.fromType(stored?.contentType)),
                ReadingStatsWindow(ReadingStatsPeriod.ALL, System.currentTimeMillis(), TimeZone.currentSystemDefault()))
            ReadingRecordSnapshot(
                summary = ReadingRecordSummary(value.totalDuration, value.activeDays,
                    value.works.singleOrNull()?.lastActivityAt),
                sessions = sessions,
                chapters = chapters,
                jumpPoints = jumpPoints,
            )
        }
    }

    suspend fun recordSession(
        manga: Content,
        startAt: Long,
        endAt: Long,
        startState: ReaderState,
        startPercent: Float,
        endState: ReaderState,
        endPercent: Float,
        force: Boolean = false,
        allowShort: Boolean = false,
    ) {
        if (!force && shouldSkip(manga)) return
        if (!allowShort && endAt - startAt < MIN_SESSION_DURATION_MS) return
        db.withTransaction {
            val anchorManga = resolveReadingRecordAnchorContent(manga)
            val storedAnchorManga = mangaRepository.storeContentAndReturn(anchorManga, replaceExisting = true)
            db.getReadingRecordDao().insertSession(
                ReadingRecordEntity(
                    mangaId = storedAnchorManga.id,
                    startAt = startAt,
                    endAt = endAt,
                    startChapterId = startState.chapterId,
                    startPage = startState.page,
                    startScroll = startState.scroll,
                    endChapterId = endState.chapterId,
                    endPage = endState.page,
                    endScroll = endState.scroll,
                    startPercent = startPercent,
                    endPercent = endPercent,
                ),
            )
        }
    }

    suspend fun recordJumpPoint(
        manga: Content,
        fromState: ReaderState,
        fromPercent: Float,
        toState: ReaderState,
        toPercent: Float,
        source: String,
        force: Boolean = false,
    ) {
        if (!force && shouldSkip(manga)) return
        db.withTransaction {
            val anchorManga = resolveReadingRecordAnchorContent(manga)
            val storedAnchorManga = mangaRepository.storeContentAndReturn(anchorManga, replaceExisting = true)
            db.getReadingRecordDao().insertJumpPoint(
                ReadingJumpPointEntity(
                    mangaId = storedAnchorManga.id,
                    createdAt = System.currentTimeMillis(),
                    fromChapterId = fromState.chapterId,
                    fromPage = fromState.page,
                    fromScroll = fromState.scroll,
                    fromPercent = fromPercent,
                    toChapterId = toState.chapterId,
                    toPage = toState.page,
                    toScroll = toState.scroll,
                    toPercent = toPercent,
                    source = source,
                ),
            )
        }
    }

    suspend fun clearForContent(mangaId: Long) = db.withTransaction {
        val dao = db.getReadingRecordDao()
        val anchorIds = resolveReadingRecordReadIds(mangaId)
        dao.clearSessions(anchorIds)
        dao.clearJumpPoints(anchorIds)
    }

    suspend fun deleteSession(id: Long) {
        db.getReadingRecordDao().deleteSession(id)
    }

    suspend fun deleteJumpPoint(id: Long) {
        db.getReadingRecordDao().deleteJumpPoint(id)
    }

    fun shouldSkip(manga: Content): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

    private fun resolveReadingRecordReadIds(mangaId: Long): List<Long> {
        return listOf(mangaId)
    }

    private fun resolveReadingRecordAnchorContent(manga: Content): Content {
        return manga
    }

    private companion object {
        const val DEFAULT_JUMP_LIMIT = 20
        const val MIN_SESSION_DURATION_MS = 5_000L
    }
}
