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
			?: return estimateFromCounts(manga, details, history.percent, history.chaptersCount)
		// Use all chapters for global progress calculation, not just current branch
		val chapters = details.chapters ?: emptyList()
		if (details.source.getContentType() in VIDEO_CONTENT_TYPES) {
			val branchChapters = chapters.filter { it.branch == chapter.branch }
			val chapterIndex = branchChapters.indexOfFirst { it.id == history.chapterId }
			if (chapterIndex < 0) {
				return PROGRESS_NONE
			}
			val result = history.percent.takeIf {
				ReadingProgress.isValid(it) && history.chaptersCount == branchChapters.size
			} ?: calculateVideoSeriesProgress(
				chapterIndex = chapterIndex,
				chaptersCount = branchChapters.size,
				episodeScroll = history.scroll,
			) ?: return PROGRESS_NONE
			historyRepository.updateProgress(manga.id, result, branchChapters.size)
			return result
		}
		val chapterRepo = if (repo.source == chapter.source) {
			repo
		} else {
			mangaRepositoryFactory.create(chapter.source)
		}
		val chaptersCount = chapters.size
		if (chaptersCount == 0) {
			return PROGRESS_NONE
		}
		val chapterIndex = chapters.indexOfFirst { x -> x.id == history.chapterId }
		if (chapterIndex < 0) {
			return estimateFromCounts(manga, details, history.percent, history.chaptersCount)
		}
		val pagesCount = chapterRepo.getPages(chapter).size
		if (pagesCount == 0) {
			return PROGRESS_NONE
		}
		val pagePercent = (history.page + 1) / pagesCount.toFloat()
		val ppc = 1f / chaptersCount
		val result = ppc * chapterIndex + ppc * pagePercent
		historyRepository.updateProgress(manga.id, result, chaptersCount)
		return result
	}

	private suspend fun estimateFromCounts(
		manga: Content,
		details: Content,
		percent: Float,
		chaptersCount: Int,
	): Float {
		val newTotal = details.chapters?.size ?: 0
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

internal fun calculateVideoSeriesProgress(
	chapterIndex: Int,
	chaptersCount: Int,
	episodeScroll: Int,
): Float? {
	if (chapterIndex !in 0 until chaptersCount) return null
	val episodePercent = episodeScroll.coerceIn(0, 10_000) / 10_000f
	return ((chapterIndex + episodePercent) / chaptersCount).coerceIn(0f, 1f)
}
