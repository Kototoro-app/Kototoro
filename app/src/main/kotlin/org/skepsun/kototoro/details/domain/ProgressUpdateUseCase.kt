package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
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
		val chapter = details.findChapterById(history.chapterId) ?: return PROGRESS_NONE
		// Use all chapters for global progress calculation, not just current branch
		val chapters = details.chapters ?: emptyList()
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
		val pagesCount = chapterRepo.getPages(chapter).size
		if (pagesCount == 0) {
			return PROGRESS_NONE
		}
		val pagePercent = (history.page + 1) / pagesCount.toFloat()
		val ppc = 1f / chaptersCount
		return ppc * chapterIndex + ppc * pagePercent
	}
}
