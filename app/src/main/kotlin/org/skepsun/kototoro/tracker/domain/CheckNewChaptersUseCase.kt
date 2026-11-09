package org.skepsun.kototoro.tracker.domain

import android.util.Log
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.MultiMutex
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toInstantOrNull
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
    private val repository: TrackingRepository,
    private val historyRepository: HistoryRepository,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val localContentRepository: LocalMangaRepository,
) {

    private val mutex = MultiMutex<Long>()

    suspend operator fun invoke(manga: Content): MangaUpdates = mutex.withLock(manga.id) {
        repository.updateTracks()
        val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
            manga = manga,
            entityId = null,
            anchorMangaId = manga.id,
            error = null,
        )
        invokeImpl(tracking)
    }

    suspend operator fun invoke(track: ContentTracking): MangaUpdates = mutex.withLock(track.anchorMangaId) {
        invokeImpl(track)
    }

    suspend operator fun invoke(manga: Content, currentChapterId: Long, percent: Float) {
        if (repository.updateReadChapter(manga.id, currentChapterId, percent, manga.chapters)) return
        if (ReadingProgress.isCompleted(percent)) {
            markCompleted(manga)
        } else {
            invoke(manga, currentChapterId)
        }
    }

    suspend operator fun invoke(manga: Content, currentChapterId: Long) = mutex.withLock(manga.id) {
        if (repository.updateReadChapter(manga.id, currentChapterId, readerChapters = manga.chapters)) return@withLock
        runCatchingCancellable {
            repository.updateTracks()
            val details = getFullContent(manga)
            val track = repository.getTrackOrNull(manga) ?: return@withLock
            val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
            val chapters = details.getChapters(branch)
            if (chapters.isEmpty()) return@withLock
            // Reading is not a check: the anchor only moves to what was read, so chapters the tracker has not seen
            // yet are still reported, and the check time stays, so the title keeps its place in the queue.
            val state = trackAfterReading(
                readIndex = chapters.indexOfFirst { x -> x.id == currentChapterId },
                anchorIndex = chapters.indexOfFirst { x -> x.id == track.lastChapterId },
                lastIndex = chapters.lastIndex,
                pending = track.newChapters,
            )
            val anchor = chapters[state.anchorIndex]
            val tracking = ContentTracking(
                anchorMangaId = track.anchorMangaId,
                entityId = track.entityId,
                preferredLocalMangaId = track.preferredLocalMangaId,
                manga = details,
                lastChapterId = anchor.id,
                lastCheck = track.lastCheck,
                lastChapterDate = if (anchor.id == track.lastChapterId) {
                    track.lastChapterDate
                } else {
                    anchor.uploadDate.toInstantOrNull() ?: track.lastChapterDate
                },
                newChapters = state.pending,
            )
            repository.mergeWith(tracking)
        }.onFailure { e ->
            e.printStackTraceDebug()
        }.isSuccess
    }

    private suspend fun markCompleted(manga: Content) = mutex.withLock(manga.id) {
        runCatchingCancellable {
            repository.updateTracks()
            val details = getFullContent(manga)
            val track = repository.getTrackOrNull(manga) ?: return@withLock
            val branch = getBranch(details, track.lastChapterId)
            val lastChapter = details.getChapters(branch)?.lastOrNull()
            val tracking = ContentTracking(
                anchorMangaId = track.anchorMangaId,
                entityId = track.entityId,
                preferredLocalMangaId = track.preferredLocalMangaId,
                manga = details,
                lastChapterId = lastChapter?.id ?: track.lastChapterId,
                lastCheck = track.lastCheck,
                lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
                newChapters = 0,
            )
            repository.mergeWith(tracking)
            repository.clearReadUpdates(manga.id)
        }.onFailure { e ->
            e.printStackTraceDebug()
        }.isSuccess
    }

    private suspend fun invokeImpl(track: ContentTracking): MangaUpdates = runCatchingCancellable {
        // The persisted manga row owns its own tracking state, so the track's anchor
        // manga is the identity. There is no Work/entity to resolve.
        val ownerId = track.anchorMangaId
        if (track.manga.isLocal) {
            // A local copy has no stored chapter list to compare with, so it keeps comparing with its anchor.
            val details = getFullContent(track.manga, forceFetch = true).copy(id = ownerId)
            return@runCatchingCancellable compare(
                track = track,
                entityId = ownerId,
                manga = details,
                branch = getBranch(details, track.lastChapterId),
                historyChapterId = historyRepository.getOne(track.manga)?.chapterId ?: 0L,
            )
        }
        // Storing the fetched list finds the new chapters (storeChapterSnapshot), as it does for the details page.
        // Most checks find nothing new, so they only ask for the chapter list. When there is something new the
        // details are refreshed too: a series usually turns finished with its last chapter, which "skip completed
        // titles" relies on.
        val repo = mangaRepositoryFactory.create(track.manga.source)
        val chaptersOnly = !track.isEmpty() && repo.refreshesChaptersAlone
        val fetched = if (chaptersOnly) {
            repo.getDetails(track.manga, ContentRepository.DetailsFetchMode.CHAPTERS_REFRESH)
        } else {
            fetchDetails(track.manga)
        }
        var (details, stored) = repository.storeCheckedSnapshot(fetched, ownerId)
        if (stored is ChapterSnapshotResult.Incomplete) throw IncompleteChapterListException()
        val reported = (stored as? ChapterSnapshotResult.Updated)?.reported.orEmpty().mapTo(HashSet()) { it.chapterId }
        if (chaptersOnly && reported.isNotEmpty()) {
            details = repository.storeCheckedSnapshot(fetchDetails(track.manga), ownerId).first
        }
        MangaUpdates.Success(
            manga = details,
            entityId = ownerId,
            anchorMangaId = ownerId,
            branch = getBranch(details, track.lastChapterId),
            newChapters = details.chapters.orEmpty().filter { it.id in reported },
            isValid = stored is ChapterSnapshotResult.Updated,
        )
    }.getOrElse { error ->
        MangaUpdates.Failure(
            manga = track.manga,
            entityId = track.anchorMangaId,
            anchorMangaId = track.anchorMangaId,
            error = error,
        )
    }.also { updates ->
        repository.updateTrack(updates.manga, updates)
    }

    private suspend fun getBranch(manga: Content, trackChapterId: Long): String? {
        historyRepository.getOne(manga)?.let {
            manga.chapters?.findById(it.chapterId)
        }?.let {
            return it.branch
        }
        manga.chapters?.findById(trackChapterId)?.let {
            return it.branch
        }
        // fallback
        return manga.getPreferredBranch(null)
    }

    private suspend fun getFullContent(manga: Content, forceFetch: Boolean = false): Content = when {
        manga.isLocal -> {
            val remote = localContentRepository.getRemoteContent(manga)
            if (remote != null) fetchDetails(remote) else manga
        }

        forceFetch || manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
        else -> manga
    }

    private suspend fun fetchDetails(manga: Content): Content {
        val repo = mangaRepositoryFactory.create(manga.source)
        return repo.getDetails(manga, ContentRepository.DetailsFetchMode.FORCE_REFRESH)
    }

    /**
     * The main functionality of tracker: check new chapters in [manga] comparing to the [track]
     */
    private fun compare(
        track: ContentTracking,
        entityId: Long,
        manga: Content,
        branch: String?,
        historyChapterId: Long,
    ): MangaUpdates.Success {
        // The comparison rules are shared with the Windows tracker (core-domain TrackRules).
        val chapters = if (track.isEmpty()) emptyList() else requireNotNull(manga.getChapters(branch))
        if (BuildConfig.DEBUG && !track.isEmpty() && chapters.findById(track.lastChapterId) == null) {
            Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
        }
        val comparison = compareTrackedChapters(
            chapters = chapters,
            id = { it.id },
            lastChapterId = track.lastChapterId,
            uploadDate = { it.uploadDate },
            lastChapterDate = track.lastChapterDate?.toEpochMilli() ?: 0L,
            historyChapterId = historyChapterId,
        )
        return MangaUpdates.Success(
            manga = manga,
            entityId = entityId,
            anchorMangaId = track.anchorMangaId,
            branch = branch,
            newChapters = reportedNewChapters(
                newChapters = comparison.newChapters,
                branch = chapters,
                id = { it.id },
                uploadDate = { it.uploadDate },
                readChapterId = historyChapterId,
                now = System.currentTimeMillis(),
            ),
            isValid = comparison.isValid,
        )
    }
}
