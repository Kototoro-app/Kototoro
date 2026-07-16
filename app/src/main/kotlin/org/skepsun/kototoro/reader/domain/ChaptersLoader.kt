package org.skepsun.kototoro.reader.domain

import android.util.LongSparseArray
import androidx.core.net.toUri
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.model.getMergeKey
import org.skepsun.kototoro.core.model.mergeRepeated
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.model.getContentType
import javax.inject.Inject

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val settings: AppSettings,
) {

	private val chapters = LongSparseArray<ContentChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: ContentDetails) = mutex.withLock {
		chapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
	}

	suspend fun loadPrevNextChapter(manga: ContentDetails, currentId: Long, isNext: Boolean): Boolean {
		val contentType = manga.toContent().source.getContentType()
		val useMerge = settings.isMergeRepeatedChapters && contentType.isManga()
		val chaptersList = if (useMerge) {
			val allBranches = manga.chapters.keys.toList()
			val rawChapters = allBranches.flatMap { manga.chapters[it].orEmpty() }
			rawChapters.mergeRepeated()
		} else {
			manga.allChapters
		}

		val currentChapter = peekChapter(currentId) ?: manga.allChapters.find { it.id == currentId }
		val index = if (currentChapter != null) {
			val currentKey = if (useMerge) currentChapter.getMergeKey() else null
			if (useMerge) {
				chaptersList.indexOfFirst { it.getMergeKey() == currentKey }
			} else {
				val predicate: (ContentChapter) -> Boolean = { it.id == currentId }
				if (isNext) chaptersList.indexOfFirst(predicate) else chaptersList.indexOfLast(predicate)
			}
		} else {
			val predicate: (ContentChapter) -> Boolean = { it.id == currentId }
			if (isNext) chaptersList.indexOfFirst(predicate) else chaptersList.indexOfLast(predicate)
		}

		if (index == -1) return false
		val newChapter = chaptersList.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
		val newPages = loadChapter(newChapter.id)
		mutex.withLock {
			if (chapterPages.chaptersSize > 1) {
				// trim pages
				if (chapterPages.size > PAGES_TRIM_THRESHOLD) {
					if (isNext) {
						chapterPages.removeFirst()
					} else {
						chapterPages.removeLast()
					}
				}
			}
			if (isNext) {
				chapterPages.addLast(newChapter.id, newPages)
			} else {
				chapterPages.addFirst(newChapter.id, newPages)
			}
		}
		return true
	}

	suspend fun keepOnlyChapter(chapterId: Long) = mutex.withLock {
		if (chapterId !in chapterPages) {
			chapterPages.clear()
			return@withLock
		}
		while (chapterPages.chaptersSize > 1) {
			if (chapterPages.first().chapterId != chapterId) {
				chapterPages.removeFirst()
			} else if (chapterPages.last().chapterId != chapterId) {
				chapterPages.removeLast()
			} else {
				break
			}
		}
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		val pages = loadChapter(chapterId)
		return mutex.withLock {
			chapterPages.clear()
			chapterPages.addLast(chapterId, pages)
			pages.isNotEmpty()
		}
	}

	suspend fun loadLocalChapters() {
		val localChapters = mutex.withLock {
			buildList(chapters.size()) {
				for (i in 0 until chapters.size()) {
					chapters.valueAt(i).takeIf { it.isLocalPageSource() }?.let(::add)
				}
			}
		}
		localChapters.forEach { chapter ->
			if (hasPages(chapter.id)) {
				return@forEach
			}
			val pages = loadChapter(chapter.id)
			mutex.withLock {
				if (chapter.id !in chapterPages) {
					chapterPages.addLast(chapter.id, pages)
				}
			}
		}
	}

	fun peekChapter(chapterId: Long): ContentChapter? = chapters[chapterId]

	fun isChapterLocal(chapterId: Long): Boolean {
		return chapters[chapterId]?.isLocalPageSource() ?: false
	}

	fun hasPages(chapterId: Long): Boolean {
		return chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<ContentPage> = synchronized(chapterPages) {
		return chapterPages.subList(chapterId).map { it.toContentPage() }
	}

	fun getPagesCount(chapterId: Long): Int {
		return chapterPages.size(chapterId)
	}

	fun last() = chapterPages.last()

	fun first() = chapterPages.first()

	fun snapshot() = chapterPages.toList()

	private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
		val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
		val basePages = if (chapter.isLocalPageSource()) {
			org.skepsun.kototoro.local.data.input.LocalContentParser(android.net.Uri.parse(chapter.url)).getPages(chapter)
		} else {
			val repo = mangaRepositoryFactory.create(chapter.source)
			repo.getPages(chapter)
		}
		return basePages.mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}

	private fun ContentChapter.isLocalPageSource(): Boolean {
		val uri = url.toUri()
		return uri.isFileUri() ||
			uri.isZipUri() ||
			uri.scheme == "content" ||
			uri.scheme == "epub" ||
			uri.scheme == "localepub" ||
			source.isLocal
	}
}
