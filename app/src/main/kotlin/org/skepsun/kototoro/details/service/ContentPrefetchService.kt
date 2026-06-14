package org.skepsun.kototoro.details.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.model.parcelable.ParcelableChapter
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.ui.CoroutineIntentService
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.isPowerSaveMode
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class ContentPrefetchService : CoroutineIntentService() {

	@Inject
	lateinit var mangaRepositoryFactory: ContentRepository.Factory

	@Inject
	lateinit var cache: MemoryContentCache

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var contentDataRepository: ContentDataRepository

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		when (intent.action) {
			ACTION_PREFETCH_DETAILS -> {
				val mangaId = intent.getLongExtra(AppRouter.KEY_ID, 0L)
				val manga = if (mangaId != 0L) {
					contentDataRepository.findPreferredLocalContentById(mangaId, withChapters = false)
						?: contentDataRepository.findContentById(mangaId, withChapters = false)
						?: intent.getParcelableExtraCompat<ParcelableContent>(EXTRA_MANGA)?.manga
				} else {
					intent.getParcelableExtraCompat<ParcelableContent>(EXTRA_MANGA)?.manga
				} ?: return
				prefetchDetails(manga)
			}

			ACTION_PREFETCH_PAGES -> prefetchPages(
				chapter = intent.getParcelableExtraCompat<ParcelableChapter>(EXTRA_CHAPTER)?.chapter
					?: return,
			)

			ACTION_PREFETCH_LAST -> prefetchLast()
		}
	}

	override fun IntentJobContext.onError(error: Throwable) = Unit

	private suspend fun prefetchDetails(manga: Content) {
		val source = mangaRepositoryFactory.create(manga.source)
		runCatchingCancellable { source.getDetails(manga) }
	}

	private suspend fun prefetchPages(chapter: ContentChapter) {
		val source = mangaRepositoryFactory.create(chapter.source)
		runCatchingCancellable { source.getPages(chapter) }
	}

	private suspend fun prefetchLast() {
		val last = historyRepository.getLastOrNull() ?: return
		if (last.isLocal) return
		val repo = mangaRepositoryFactory.create(last.source)
		val details = runCatchingCancellable { repo.getDetails(last) }.getOrNull() ?: return
		val chapters = details.chapters
		if (chapters.isNullOrEmpty()) {
			return
		}
		val history = historyRepository.getOne(last)
		val chapter = if (history == null) {
			chapters.firstOrNull()
		} else {
			chapters.findById(history.chapterId) ?: chapters.firstOrNull()
		} ?: return
		runCatchingCancellable { repo.getPages(chapter) }
	}

	companion object {

		private const val EXTRA_MANGA = "manga"
		private const val EXTRA_CHAPTER = "manga"
		private const val ACTION_PREFETCH_DETAILS = "details"
		private const val ACTION_PREFETCH_PAGES = "pages"
		private const val ACTION_PREFETCH_LAST = "last"

		fun prefetchDetails(context: Context, manga: Content) {
			if (!isPrefetchAvailable(context, manga.source)) return
			val intent = Intent(context, ContentPrefetchService::class.java)
			intent.action = ACTION_PREFETCH_DETAILS
			intent.putExtra(EXTRA_MANGA, ParcelableContent(manga))
			intent.putExtra(AppRouter.KEY_ID, manga.id)
			tryStart(context, intent)
		}

		fun prefetchPages(context: Context, chapter: ContentChapter) {
			if (!isPrefetchAvailable(context, chapter.source)) return
			val intent = Intent(context, ContentPrefetchService::class.java)
			intent.action = ACTION_PREFETCH_PAGES
			intent.putExtra(EXTRA_CHAPTER, ParcelableChapter(chapter))
			tryStart(context, intent)
		}

		fun prefetchLast(context: Context) {
			if (!isPrefetchAvailable(context, null)) return
			val intent = Intent(context, ContentPrefetchService::class.java)
			intent.action = ACTION_PREFETCH_LAST
			tryStart(context, intent)
		}

		private fun isPrefetchAvailable(context: Context, source: ContentSource?): Boolean {
			if (source == LocalMangaSource || context.isPowerSaveMode()) {
				return false
			}
			val entryPoint = EntryPointAccessors.fromApplication(
				context,
				PrefetchCompanionEntryPoint::class.java,
			)
			return entryPoint.settings.isContentPrefetchEnabled
		}

		private fun tryStart(context: Context, intent: Intent) {
			try {
				context.startService(intent)
			} catch (e: IllegalStateException) {
				// probably app is in background
				e.printStackTraceDebug()
			}
		}
	}
}
