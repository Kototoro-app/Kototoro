package org.skepsun.kototoro.local.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.model.ids
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.recoverCatchingCancellable
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class DeleteReadChaptersUseCase @Inject constructor(
	private val localContentRepository: LocalMangaRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
) {

	suspend operator fun invoke(manga: Content): Int {
		val localContent = if (manga.isLocal) {
			LocalContent(manga)
		} else {
			checkNotNull(localContentRepository.findSavedContent(manga)) { "Cannot find local manga" }
		}
		val task = getDeletionTask(localContent) ?: return 0
		localContentRepository.deleteChapters(task.manga.manga, task.chaptersIds)
		return task.chaptersIds.size
	}

	suspend operator fun invoke(): Int {
		val list = localContentRepository.getList(0, null, null)
		if (list.isEmpty()) {
			return 0
		}
		return channelFlow {
			for (manga in list) {
				launch(Dispatchers.Default) {
					val task = runCatchingCancellable {
						getDeletionTask(LocalContent(manga))
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrNull()
					if (task != null) {
						send(task)
					}
				}
			}
		}.buffer().map {
			runCatchingCancellable {
				localContentRepository.deleteChapters(it.manga.manga, it.chaptersIds)
				it.chaptersIds.size
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrDefault(0)
		}.fold(0) { acc, x -> acc + x }
	}

	private suspend fun getDeletionTask(manga: LocalContent): DeletionTask? {
		val history = historyRepository.getOne(manga.manga) ?: return null
		val chapters = getAllChapters(manga)
		if (chapters.isEmpty()) {
			return null
		}
		val historyChapter = chapters.findById(history.chapterId) ?: return null
		val branch = historyChapter.branch
		val filteredChapters = chapters
			.filter { x -> x.branch == branch }
			.takeWhile { it.id != historyChapter.id }
		return if (filteredChapters.isEmpty()) {
			null
		} else {
			DeletionTask(
				manga = manga,
				chaptersIds = filteredChapters.ids(),
			)
		}
	}

	private suspend fun getAllChapters(manga: LocalContent): List<ContentChapter> = runCatchingCancellable {
		val remoteContent = checkNotNull(localContentRepository.getRemoteContent(manga.manga))
		checkNotNull(mangaRepositoryFactory.create(remoteContent.source).getDetails(remoteContent).chapters)
	}.recoverCatchingCancellable {
		checkNotNull(
			manga.manga.chapters.let {
				if (it.isNullOrEmpty()) {
					localContentRepository.getDetails(manga.manga).chapters
				} else {
					it
				}
			},
		)
	}.getOrDefault(manga.manga.chapters.orEmpty())

	private class DeletionTask(
		val manga: LocalContent,
		val chaptersIds: Set<Long>,
	)
}
