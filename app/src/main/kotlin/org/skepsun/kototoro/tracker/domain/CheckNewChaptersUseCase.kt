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
import java.time.Instant
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
		if (ReadingProgress.isCompleted(percent)) {
			markCompleted(manga)
		} else {
			invoke(manga, currentChapterId)
		}
	}

	suspend operator fun invoke(manga: Content, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullContent(manga)
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastNewChapterIndex = chapters.size - track.newChapters
			val lastChapter = chapters.lastOrNull()
			val tracking = ContentTracking(
				anchorMangaId = track.anchorMangaId,
				entityId = track.entityId,
				preferredLocalMangaId = track.preferredLocalMangaId,
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					track.newChapters == 0 -> 0
					chapterIndex < 0 -> track.newChapters
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> track.newChapters
				},
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
				lastCheck = Instant.now(),
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
		val entityId = checkNotNull(track.entityId) { "Tracking requires a valid Work identity" }
		check(repository.hasValidTrackingIdentity(track)) { "Tracking identity requires migration review" }
		val executionSeed = repository.getExecutionTrackingContent(track)
		val details = getFullContent(executionSeed, forceFetch = true).copy(id = track.anchorMangaId)
		compare(
			track = track,
			entityId = entityId,
			manga = details,
			branch = getBranch(details, track.lastChapterId),
		)
	}.getOrElse { error ->
		MangaUpdates.Failure(
			manga = repository.getExecutionTrackingContentOrNull(track.anchorMangaId) ?: track.manga,
			entityId = track.entityId,
			anchorMangaId = track.anchorMangaId,
			error = error,
		)
	}.also { updates ->
		repository.saveUpdates(updates)
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
	): MangaUpdates.Success {
		if (track.isEmpty()) {
			// first check or manga was empty on last check
			return MangaUpdates.Success(
				manga = manga,
				entityId = entityId,
				anchorMangaId = track.anchorMangaId,
				branch = branch,
				newChapters = emptyList(),
				isValid = false,
			)
		}
		val chapters = requireNotNull(manga.getChapters(branch))
		if (BuildConfig.DEBUG && chapters.findById(track.lastChapterId) == null) {
			Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
		}
		val newChapters = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
		return when {
			newChapters.isEmpty() -> {
				MangaUpdates.Success(
					manga = manga,
					entityId = entityId,
					anchorMangaId = track.anchorMangaId,
					branch = branch,
					newChapters = emptyList(),
					isValid = chapters.lastOrNull()?.id == track.lastChapterId,
				)
			}

			newChapters.size == chapters.size -> {
				MangaUpdates.Success(
					manga = manga,
					entityId = entityId,
					anchorMangaId = track.anchorMangaId,
					branch = branch,
					newChapters = emptyList(),
					isValid = false,
				)
			}

			else -> {
				MangaUpdates.Success(
					manga = manga,
					entityId = entityId,
					anchorMangaId = track.anchorMangaId,
					branch = branch,
					newChapters = newChapters,
					isValid = true,
				)
			}
		}
	}
}
