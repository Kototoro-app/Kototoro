package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.domain.afterFailedCheck
import org.skepsun.kototoro.tracker.domain.afterSuccessfulCheck
import org.skepsun.kototoro.tracker.domain.compareTrackedChapters
import org.skepsun.kototoro.tracker.domain.recordUpdateBatch
import org.skepsun.kototoro.tracker.domain.repairLegacyUpdateCounters
import org.skepsun.kototoro.tracker.domain.trackedLastChapterDate

data class SourceTrackReport(val checked: Int, val withUpdates: Int, val failed: Int, val newChapters: Int)

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
) {
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

    suspend fun checkAll(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): SourceTrackReport {
        val ids = syncAnchors()
        database.useWriterConnection { connection ->
            connection.immediateTransaction { database.repairLegacyUpdateCounters() }
        }
        var withUpdates = 0
        var failed = 0
        var newChapters = 0
        ids.forEachIndexed { index, id ->
            when (val result = check(id)) {
                null -> failed++
                else -> if (result > 0) { withUpdates++; newChapters += result }
            }
            onProgress(index + 1, ids.size)
        }
        return SourceTrackReport(ids.size, withUpdates, failed, newChapters)
    }

    /** Checks one tracked work; returns the new chapter count, or null when the check failed. */
    suspend fun check(mangaId: Long): Int? {
        val stored = library.find(mangaId) ?: return null
        val track = database.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
        // Android turns any failure of a check into a failed result instead of aborting the whole run.
        return try {
            checkFetched(mangaId, track, fetchDetails(stored).copy(id = mangaId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            database.getTracksDao().upsert(track.afterFailedCheck(e.toString(), now()))
            null
        }
    }

    private suspend fun checkFetched(mangaId: Long, track: TrackEntity, details: SourceContent): Int {
        val chapters = details.chapters.orEmpty()
        val branch = branchOf(details, track.lastChapterId)
        val branchChapters = chapters.filter { it.branch == branch }
        val comparison = compareTrackedChapters(branchChapters, SourceChapter::id, track.lastChapterId)
        val time = now()
        library.save(details)
        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
                database.getTracksDao().upsert(track.afterSuccessfulCheck(
                    anchorMangaId = mangaId,
                    lastChapterId = branchChapters.lastOrNull()?.id ?: 0L,
                    newChapterCount = comparison.newChapters.size,
                    isValid = comparison.isValid,
                    lastChapterDate = trackedLastChapterDate(comparison.newChapters.map { it.uploadDate },
                        chapters.lastOrNull()?.uploadDate, time),
                    now = time,
                ))
                database.recordUpdateBatch(mangaId, comparison.newChapters.mapTo(HashSet()) { it.id }, time)
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
