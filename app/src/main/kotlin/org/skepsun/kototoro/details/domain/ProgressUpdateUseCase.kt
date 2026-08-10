package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject

class ProgressUpdateUseCase @Inject constructor(
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val localContentRepository: LocalMangaRepository,
	private val networkState: NetworkState,
	private val historyRepository: HistoryRepository,
) {

	suspend operator fun invoke(manga: Content): Float {
		val history = historyRepository.getOne(manga) ?: return PROGRESS_NONE
		val seed = if (manga.isLocal) {
			localContentRepository.getRemoteContent(manga) ?: manga
		} else {
			manga
		}
		if (!seed.isLocal && !networkState.value) {
			return PROGRESS_NONE
		}
		val repo = mangaRepositoryFactory.create(seed.source)
		val details = if (manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
			repo.getDetails(seed)
		} else {
			seed
		}
		val chapter = details.findChapterById(history.chapterId)
			?: return estimateFromCounts(
				manga = manga,
				details = details,
				previousDetails = seed,
				chapterId = history.chapterId,
				percent = history.percent,
				chaptersCount = history.chaptersCount,
			)
		val chapters = details.chapters ?: emptyList()
		if (details.source.getContentType() in VIDEO_CONTENT_TYPES) {
			val calculated = calculateGroupedChapterProgress(
				chapters = chapters,
				chapterId = history.chapterId,
				chapterPercent = history.scroll.coerceIn(0, 10_000) / 10_000f,
			) ?: return PROGRESS_NONE
			val result = history.percent.takeIf {
				ReadingProgress.isValid(it) && history.chaptersCount == calculated.chaptersCount
			} ?: calculated.percent
			historyRepository.updateProgress(manga.id, result, calculated.chaptersCount)
			return result
		}
		val chapterRepo = if (repo.source == chapter.source) {
			repo
		} else {
			mangaRepositoryFactory.create(chapter.source)
		}
		val pagesCount = chapterRepo.getPages(chapter).size
		if (pagesCount == 0) {
			return PROGRESS_NONE
		}
		val pagePercent = (history.page + 1) / pagesCount.toFloat()
		val calculated = calculateGroupedChapterProgress(
			chapters = chapters,
			chapterId = history.chapterId,
			chapterPercent = pagePercent,
		) ?: return PROGRESS_NONE
		historyRepository.updateProgress(manga.id, calculated.percent, calculated.chaptersCount)
		return calculated.percent
	}

	private suspend fun estimateFromCounts(
		manga: Content,
		details: Content,
		previousDetails: Content,
		chapterId: Long,
		percent: Float,
		chaptersCount: Int,
	): Float {
		val previousChapter = previousDetails.findChapterById(chapterId)
			?: manga.findChapterById(chapterId)
			?: return percent.takeIf(ReadingProgress::isValid) ?: PROGRESS_NONE
		val newTotal = details.chapters.orEmpty().count { it.branch == previousChapter.branch }
		if (newTotal == 0 || chaptersCount <= 0 || !ReadingProgress.isValid(percent)) {
			return PROGRESS_NONE
		}
		val estimated = (percent * chaptersCount / newTotal).coerceIn(0f, 1f)
		historyRepository.updateProgress(manga.id, estimated, newTotal)
		return estimated
	}

	private companion object {
		val VIDEO_CONTENT_TYPES = setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
	}
}

internal data class GroupedChapterProgress(
	val percent: Float,
	val chaptersCount: Int,
)

internal fun calculateGroupedChapterProgress(
	chapters: List<ContentChapter>,
	chapterId: Long,
	chapterPercent: Float,
): GroupedChapterProgress? {
	val currentChapter = chapters.firstOrNull { it.id == chapterId } ?: return null
	val branchChapters = chapters.filter { it.branch == currentChapter.branch }
	val chapterIndex = branchChapters.indexOfFirst { it.id == chapterId }
	if (chapterIndex < 0 || branchChapters.isEmpty()) return null
	return GroupedChapterProgress(
		percent = ((chapterIndex + chapterPercent.coerceIn(0f, 1f)) / branchChapters.size).coerceIn(0f, 1f),
		chaptersCount = branchChapters.size,
	)
}
