package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.source.SourceChallengeException
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceErrorCode
import org.skepsun.kototoro.core.source.SourceRateLimitException
import org.skepsun.kototoro.core.source.SourceRemoteException
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.domain.SourceAnswer
import org.skepsun.kototoro.tracker.domain.SourceCheck
import org.skepsun.kototoro.tracker.domain.afterFailedCheck
import org.skepsun.kototoro.tracker.domain.afterSuccessfulCheck
import org.skepsun.kototoro.tracker.domain.checkBySource
import org.skepsun.kototoro.tracker.domain.repairLegacyUpdateCounters
import org.skepsun.kototoro.tracker.domain.trackedLastChapterDate
import org.skepsun.kototoro.tracker.domain.ChapterSnapshotResult
import org.skepsun.kototoro.tracker.domain.IncompleteChapterListException
import org.skepsun.kototoro.tracker.domain.refreshPendingUpdateCount
import org.skepsun.kototoro.tracker.domain.isTrackDue
import org.skepsun.kototoro.tracker.domain.isEmptyTrack
import org.skepsun.kototoro.tracker.domain.updateTrackSchedule

/** [skipped] titles were not checked: their source asked for a browser challenge earlier in the run. */
data class SourceTrackReport(
    val checked: Int,
    val withUpdates: Int,
    val failed: Int,
    val newChapters: Int,
    val skipped: Int = 0,
)

/**
 * New-chapter tracking over the shared `tracks`/`track_logs` tables, with the comparison and row rules shared with
 * Android (core-domain TrackRules). Scope is Android's default: favourites in categories with tracking enabled.
 */
class SourceTracker(
    private val database: MangaDatabase,
    private val library: SourceLibrary,
    private val fetchDetails: suspend (SourceContent) -> SourceContent,
    /** Android's preferred branch fallback for a work whose reading position is unknown. */
    private val preferredBranch: (SourceContent) -> String?,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** Returns null for sources that cannot refresh chapters without also fetching details. */
    private val fetchChapters: (suspend (SourceContent) -> SourceContent?)? = null,
) {
    /**
     * Sources a run gave up on because of rate limits, and until when later runs leave them alone. Android keeps this
     * in preferences; the desktop app runs for long, so this instance's memory is enough there.
     */
    private val sourceCooldowns = HashMap<String, Long>()

    suspend fun trackedCategoryCount(): Int = database.getFavouriteCategoriesDao().findAll().count { it.track }

    suspend fun enableTrackingForAllCategories() = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            val dao = database.getFavouriteCategoriesDao()
            dao.findAll().filterNot { it.track }.forEach { dao.updateTracking(it.categoryId.toLong(), true) }
        }
    }

    /** Creates rows for newly tracked works and drops rows that left the scope; returns the tracked ids. */
    suspend fun syncAnchors(): List<Long> = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            val tracked = database.getFavouriteCategoriesDao().findAll().filter { it.track }
                .map { it.categoryId.toLong() }.toSet()
            val desired = database.getFavouritesDao().findAllActiveEntries()
                .filter { it.categoryId in tracked }.map { it.mangaId }.distinct()
            val dao = database.getTracksDao()
            val existing = dao.findAllIds().toMutableSet()
            for (id in desired) if (!existing.remove(id)) dao.upsert(TrackEntity.create(id))
            for (id in existing) dao.delete(id)
            desired
        }
    }

    /**
     * Checks the tracked works; a background run ([respectSchedule]) only those due at their release pace
     * ([isTrackDue]), a run the user asked for all of them.
     */
    suspend fun checkAll(
        respectSchedule: Boolean = false,
        skipCompleted: Boolean = false,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        /** The running counts (checked so far) out of [total]: once before the first work, then after each one. */
        onCounts: suspend (counts: SourceTrackReport, total: Int) -> Unit = { _, _ -> },
    ): SourceTrackReport {
        val tracked = syncAnchors()
        val time = now()
        val ids = if (respectSchedule) {
            tracked.filter { id -> isTrackDue(database.getTrackScheduleDao().find(id)?.nextCheckTime ?: 0L, time) }
        } else {
            tracked
        }
        database.useWriterConnection { connection ->
            connection.immediateTransaction { database.repairLegacyUpdateCounters() }
        }
        var withUpdates = 0
        var failed = 0
        var newChapters = 0
        var skipped = 0
        var done = 0
        // Android's scheduling: sources in turn, a few checks per source, and a challenged source skipped for the
        // rest of the run instead of failing every remaining title of it.
        val items = ids.map { id -> id to library.find(id) }
            .filterNot { (_, stored) -> skipCompleted && stored?.state == "FINISHED" }
        onCounts(SourceTrackReport(0, 0, 0, 0, 0), items.size)
        checkBySource(
            items = items,
            sourceOf = { (_, stored) -> stored?.source?.name.orEmpty() },
            now = now,
            answerOf = { outcome: CheckOutcome ->
                when {
                    outcome.error.isChallenge() -> SourceAnswer.Challenged
                    else -> SourceRateLimitException.find(outcome.error)
                        ?.let { SourceAnswer.RateLimited(it.retryAfterMillis) }
                        ?: SourceAnswer.Answered
                }
            },
            cooldowns = sourceCooldowns.toMap(),
            onCooldown = { source, until -> sourceCooldowns[source] = until },
        ) { (id, stored) -> checkStored(id, stored) }.collect { check ->
            when (check) {
                is SourceCheck.Skipped -> skipped++
                is SourceCheck.Checked -> when (val count = check.result.newChapters) {
                    null -> failed++
                    else -> if (count > 0) { withUpdates++; newChapters += count }
                }
            }
            onProgress(++done, items.size)
            onCounts(SourceTrackReport(done, withUpdates, failed, newChapters, skipped), items.size)
        }
        return SourceTrackReport(items.size, withUpdates, failed, newChapters, skipped)
    }

    /** Checks one tracked work; returns the new chapter count, or null when the check failed. */
    suspend fun check(mangaId: Long): Int? = checkStored(mangaId, library.find(mangaId)).newChapters

    /** [newChapters] is null when the check failed, with [error] saying why when there was one. */
    private class CheckOutcome(val newChapters: Int?, val error: Throwable? = null)

    private suspend fun checkStored(mangaId: Long, stored: SourceContent?): CheckOutcome {
        stored ?: return CheckOutcome(null)
        val track = database.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
        // Android turns any failure of a check into a failed result instead of aborting the whole run.
        return try {
            val chapters = if (!isEmptyTrack(track.lastChapterId)) fetchChapters?.invoke(stored) else null
            val details = (chapters ?: fetchDetails(stored)).copy(id = mangaId)
            CheckOutcome(checkFetched(mangaId, track, details, refreshDetails = chapters != null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val time = now()
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    database.getTracksDao().upsert(track.afterFailedCheck(e.toString(), time))
                    database.updateTrackSchedule(mangaId, succeeded = false, now = time)
                }
            }
            CheckOutcome(null, e)
        }
    }

    private fun Throwable?.isChallenge(): Boolean = generateSequence(this) { e -> e.cause?.takeIf { it !== e } }
        .any { e ->
            e is SourceChallengeException ||
                (e is SourceRemoteException && e.error.code == SourceErrorCode.CHALLENGE_REQUIRED)
        }

    private suspend fun checkFetched(
        mangaId: Long,
        track: TrackEntity,
        details: SourceContent,
        refreshDetails: Boolean,
    ): Int {
        val chapters = details.chapters.orEmpty()
        val branch = branchOf(details, track.lastChapterId)
        val time = now()
        // Storing the list finds the new chapters, as on Android (storeChapterSnapshot).
        val stored = library.save(details, fallbackBranch = branch, now = time)
        if (stored is ChapterSnapshotResult.Incomplete) throw IncompleteChapterListException()
        val reported = (stored as? ChapterSnapshotResult.Updated)?.reported.orEmpty()
        if (refreshDetails && reported.isNotEmpty()) {
            val refreshed = fetchDetails(details).copy(id = mangaId)
            val result = library.save(refreshed, fallbackBranch = branch, now = time)
            if (result is ChapterSnapshotResult.Incomplete) throw IncompleteChapterListException()
        }
        val branchChapters = chapters.filter { it.branch == branch }
        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
                database.getTracksDao().upsert((database.getTracksDao().find(mangaId) ?: track).afterSuccessfulCheck(
                    anchorMangaId = mangaId,
                    lastChapterId = branchChapters.lastOrNull()?.id ?: 0L,
                    newChapterCount = reported.size,
                    isValid = stored is ChapterSnapshotResult.Updated,
                    lastChapterDate = trackedLastChapterDate(reported.map { it.uploadDate },
                        chapters.lastOrNull()?.uploadDate, time),
                    now = time,
                ))
                database.refreshPendingUpdateCount(mangaId)
                database.updateTrackSchedule(mangaId, succeeded = true, now = time)
                reported.size
            }
        }
    }

    /** Opening an update acknowledges the notification without completing its chapters. */
    suspend fun markSeen(mangaId: Long) = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            database.getTrackLogsDao().markUnreadAsReadByOwner(mangaId)
        }
    }

    /** Android: the history branch, else the branch of the last known chapter, else the preferred branch. */
    private suspend fun branchOf(details: SourceContent, lastChapterId: Long): String? {
        val chapters = details.chapters.orEmpty()
        library.progress(details.id)?.let { history -> chapters.firstOrNull { it.id == history.chapterId } }
            ?.let { return it.branch }
        chapters.firstOrNull { it.id == lastChapterId }?.let { return it.branch }
        return preferredBranch(details)
    }
}
