package org.skepsun.kototoro.details.ui.pager

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.toChipModel
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.LocaleStringComparator
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.combine
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.details.ui.DetailsActivity
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.mapChapters
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadTaskKind
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.reader.ui.ReaderActivity
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.video.domain.resolveVideoCandidates
import org.skepsun.kototoro.video.ui.VideoPlayerActivity
import org.skepsun.kototoro.video.ui.VideoChaptersViewModel
import java.time.Instant

abstract class ChaptersPagesViewModel(
	@JvmField protected val settings: AppSettings,
	@JvmField protected val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val deleteLocalContentUseCase: DeleteLocalContentUseCase,
	protected val mangaRepositoryFactory: ContentRepository.Factory,
	private val localStorageChanges: SharedFlow<LocalContent?>,
) : BaseViewModel() {

	val mangaDetails = MutableStateFlow<ContentDetails?>(null)
	val readingState = MutableStateFlow<ReaderState?>(null)
	
	data class QualityProbeResult(val snapshot: Set<Long>, val qualities: List<String>)
	val onShowVideoQualityDialog = MutableEventFlow<QualityProbeResult>()

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()
	val onContentRemoved = MutableEventFlow<Content>()

	private val chaptersQuery = MutableStateFlow("")
	val chapterQuery: StateFlow<String> = chaptersQuery
	val selectedBranch = MutableStateFlow<String?>(null)

	val manga = mangaDetails.map { x -> x?.toContent() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val coverUrl = mangaDetails.map { x -> x?.coverUrl }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isChaptersReversed = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_REVERSE_CHAPTERS,
		valueProducer = { isChaptersReverse },
	)

	val isChaptersInGridView = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
		valueProducer = { isChaptersGridView },
	)

	val isHideReadChapters = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_HIDE_READ_CHAPTERS,
		valueProducer = { isHideReadChapters },
	)

	val isMergeRepeatedChapters = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_MERGE_REPEATED_CHAPTERS,
		valueProducer = { isMergeRepeatedChapters },
	)

	val showMergeRepeatedChapters = mangaDetails.map { it?.chapters?.size ?: 0 > 1 }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val isDownloadedOnly = MutableStateFlow(false)

	val newChaptersCount = mangaDetails.flatMapLatest { d ->
		if (d?.isLocal == false) {
			interactor.observeNewChapters(d.id)
		} else {
			flowOf(0)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val emptyReason: StateFlow<EmptyContentReason?> = combine(
		mangaDetails,
		isLoading,
		onError.onStart { emit(null) },
	) { details, loading, error ->
		when {
			details == null || loading -> null
			details.chapters.isNotEmpty() -> null
			details.toContent().state == ContentState.RESTRICTED -> EmptyContentReason.RESTRICTED
			error != null -> EmptyContentReason.LOADING_ERROR
			else -> EmptyContentReason.NO_CHAPTERS
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

	val bookmarks = mangaDetails.flatMapLatest {
		if (it != null) {
			bookmarksRepository.observeBookmarks(it.toContent()).withErrorHandling()
		} else {
			flowOf(emptyList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	private val downloadInvalidation = MutableStateFlow(0)

	private val baseChaptersFlow = combine(
		mangaDetails,
		readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
		selectedBranch,
		newChaptersCount,
		bookmarks,
		isChaptersInGridView,
		isDownloadedOnly,
		isMergeRepeatedChapters,
	) { manga, currentChapterId, branch, news, bookmarks, grid, downloadedOnly, mergeRepeated ->
		val baseChapters = if (mergeRepeated && manga != null) {
			val allBranches = manga.chapters.keys.toList()
			if (allBranches.size > 1) {
				allBranches.flatMap { b ->
					manga.mapChapters(
						currentChapterId = currentChapterId,
						newCount = news,
						branch = b,
						bookmarks = bookmarks,
						isGrid = grid,
						isDownloadedOnly = downloadedOnly,
						shareProgressAcrossBranches = true,
					)
				}
			} else {
				manga.mapChapters(
					currentChapterId = currentChapterId,
					newCount = news,
					branch = branch,
					bookmarks = bookmarks,
					isGrid = grid,
					isDownloadedOnly = downloadedOnly,
				)
			}
		} else {
			manga?.mapChapters(
				currentChapterId = currentChapterId,
				newCount = news,
				branch = branch,
				bookmarks = bookmarks,
				isGrid = grid,
				isDownloadedOnly = downloadedOnly,
			).orEmpty()
		}
		expandEpubChaptersIfNeeded(baseChapters)
	}

	val chapters = combine(
		combine(baseChaptersFlow, downloadInvalidation) { list, _ -> list },
		isChaptersReversed,
		isHideReadChapters,
		isMergeRepeatedChapters,
		chaptersQuery,
	) { list, reversed, hideReadChapters, mergeRepeatedChapters, query ->
		val ordered = if (reversed) list.asReversed() else list
		val filtered = if (hideReadChapters) ordered.filterReadBeforeCurrent() else ordered
		val merged = if (mergeRepeatedChapters) filtered.mergeRepeated() else filtered
		merged.filterSearch(query)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	protected fun notifyDownloadChanged() {
		downloadInvalidation.value = downloadInvalidation.value + 1
	}
	
	/**
	 * Expand EPUB chapters by loading mappings from database.
	 * This is a hook that can be overridden by subclasses to provide EPUB expansion.
	 * Default implementation returns chapters as-is.
	 */
	protected open suspend fun expandEpubChaptersIfNeeded(chapters: List<ChapterListItem>): List<ChapterListItem> {
		return chapters
	}

	val quickFilter = combine(
		mangaDetails,
		selectedBranch,
	) { details, branch ->
		val branches = details?.chapters?.toList()?.sortedWithSafe(
			compareBy(LocaleStringComparator()) { it.first },
		).orEmpty()
		if (branches.size > 1) {
			branches.map {
				val option = ListFilterOption.Branch(titleText = it.first, chaptersCount = it.second.size)
				option.toChipModel(isChecked = it.first == branch)
			}
		} else {
			emptyList()
		}
	}

	init {
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect { onDownloadComplete(it) }
		}
		launchJob(Dispatchers.Default) {
			mangaDetails.collect { details ->
				val content = details?.toContent() ?: return@collect
				android.util.Log.d(
					"ChaptersPagesViewModel",
					"mangaDetails.collect: mangaId=${content.id}, chapterCount=${content.chapters?.size ?: 0}, selectedBranch=${selectedBranch.value}, branches=${details.chapters.mapValues { it.value.size }}",
				)
				if (content.chapters.isNullOrEmpty()) {
					return@collect
				}
				val currentBranch = selectedBranch.value
				android.util.Log.d(
					"ChaptersPagesViewModel",
					"mangaDetails.collect: currentBranch=$currentBranch, currentBranchCount=${details.chapters[currentBranch].orEmpty().size}",
				)
				if (currentBranch == null || details.chapters[currentBranch].isNullOrEmpty()) {
					val preferredBranch = content.getPreferredBranch(null)
					android.util.Log.d(
						"ChaptersPagesViewModel",
						"mangaDetails.collect: switching selectedBranch from $currentBranch to $preferredBranch",
					)
					selectedBranch.value = preferredBranch
				}
			}
		}
	}

	fun setChaptersReversed(newValue: Boolean) {
		settings.isChaptersReverse = newValue
	}

	fun setChaptersInGridView(newValue: Boolean) {
		settings.isChaptersGridView = newValue
	}

	fun setHideReadChapters(newValue: Boolean) {
		settings.isHideReadChapters = newValue
	}

	fun setMergeRepeatedChapters(newValue: Boolean) {
		settings.isMergeRepeatedChapters = newValue
	}

	fun setSelectedBranch(branch: String?) {
		selectedBranch.value = branch
	}

	fun performChapterSearch(query: String?) {
		chaptersQuery.value = query?.trim().orEmpty()
	}

	fun getContentOrNull(): Content? = mangaDetails.value?.toContent()

	fun requireContent() = mangaDetails.requireValue().toContent()

	fun markChapterAsCurrent(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue()
			// Use all chapters for global progress calculation
			val allChapters = manga.allChapters
			val chapterIndex = allChapters.indexOfFirst { it.id == chapterId }
			check(chapterIndex in allChapters.indices) { "Chapter not found" }
			val percent = chapterIndex / allChapters.size.toFloat()
			historyRepository.addOrUpdate(
				manga = manga.toContent(),
				chapterId = chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}
	}

	fun download(chapterId: Long, isMeteredNetworkAllowed: Boolean, preferredQuality: String? = null) {
		download(setOf(chapterId), isMeteredNetworkAllowed, preferredQuality)
	}

	fun setBookmarksForChapters(chapterIds: Set<Long>, removeExisting: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.value?.toContent() ?: return@launchJob
			val chapterItems = chapters.value.filter { it.chapter.id in chapterIds }
			val bookmarksByChapter = bookmarks.value
				.filter { it.chapterId in chapterIds }
				.groupBy { it.chapterId }
			if (removeExisting) {
				val bookmarkIdsToRemove = bookmarksByChapter.values
					.flatten()
					.mapTo(LinkedHashSet()) { it.pageId }
				if (bookmarkIdsToRemove.isNotEmpty()) {
					bookmarksRepository.removeBookmarks(bookmarkIdsToRemove)
				}
				return@launchJob
			}
			for (item in chapterItems) {
				if (item.chapter.id in bookmarksByChapter) {
					continue
				}
				val bookmark = Bookmark(
					manga = manga,
					pageId = item.chapter.id,
					chapterId = item.chapter.id,
					page = 0,
					scroll = 0,
					imageUrl = manga.coverUrl.orEmpty(),
					createdAt = Instant.now(),
					percent = 0f,
				)
				bookmarksRepository.addBookmark(bookmark)
			}
		}
	}

	fun probeAndDownload(snapshot: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.value?.toContent() ?: return@launchJob
			if (manga.source.getContentType() != ContentType.VIDEO) return@launchJob

			val chapterId = snapshot.firstOrNull() ?: return@launchJob
			val chapter = chapters.value.find { it.chapter.id == chapterId }?.chapter ?: return@launchJob
			val repo = mangaRepositoryFactory.create(manga.source)

			val qualities = runCatchingCancellable {
				repo.resolveVideoCandidates(chapter)
					.map { it.title.trim() }
					.filter { it.isNotEmpty() }
					.distinct()
			}.getOrNull()

			if (!qualities.isNullOrEmpty()) {
				onShowVideoQualityDialog.call(QualityProbeResult(snapshot, qualities))
			} else {
				// Fallback to default flow without specific quality
				onShowVideoQualityDialog.call(QualityProbeResult(snapshot, emptyList()))
			}
		}
	}

	fun download(snapshot: Set<Long>, isMeteredNetworkAllowed: Boolean, preferredQuality: String? = null) {
		val manga = mangaDetails.value?.toContent() ?: return
		val items = chapters.value.filter { it.chapter.id in snapshot }

			val task = DownloadTask.createExecutionTask(
				executionMangaId = manga.id,
				displayMangaId = manga.id,
				isPaused = false,
				isSilent = false,
				executionChapterIds = items.map { it.chapter.id }.toLongArray(),
				executionChapterRefs = items.map { ExecutionChapterRef.fromChapter(it.chapter) },
				destination = null,
				format = null,
				allowMeteredNetwork = isMeteredNetworkAllowed,
				preferredQuality = preferredQuality,
		)
		launchJob(Dispatchers.Default) {
			downloadScheduler.schedule(setOf(manga to task))
			onDownloadStarted.call(Unit)
		}
	}

	fun prepareTranslation(snapshot: Set<Long>, isMeteredNetworkAllowed: Boolean) {
		schedulePreparation(snapshot, DownloadTaskKind.PREPARE_TRANSLATION, isMeteredNetworkAllowed)
	}

	fun prepareSuperResolution(snapshot: Set<Long>) {
		schedulePreparation(snapshot, DownloadTaskKind.PREPARE_SUPER_RESOLUTION, true)
	}

	private fun schedulePreparation(
		snapshot: Set<Long>,
		kind: DownloadTaskKind,
		isMeteredNetworkAllowed: Boolean,
	) {
		val manga = mangaDetails.value?.toContent() ?: return
		val items = chapters.value.filter { it.chapter.id in snapshot }
			.filter { it.isDownloaded || it.chapter.source.isLocal }
		if (items.isEmpty()) {
			return
		}
			val task = DownloadTask.createExecutionTask(
				executionMangaId = manga.id,
				displayMangaId = manga.id,
				isPaused = false,
				isSilent = snapshot.size == 1,
				executionChapterIds = items.map { it.chapter.id }.toLongArray(),
				executionChapterRefs = items.map { ExecutionChapterRef.fromChapter(it.chapter) },
				destination = null,
				format = null,
				allowMeteredNetwork = isMeteredNetworkAllowed,
				kind = kind,
		)
		launchJob(Dispatchers.Default) {
			downloadScheduler.schedule(setOf(manga to task))
			onDownloadStarted.call(Unit)
		}
	}

	fun deleteLocal() {
		val m = mangaDetails.value?.local?.manga
		if (m == null) {
			errorEvent.call(FileNotFoundException())
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalContentUseCase(m)
			onContentRemoved.call(m)
		}
	}

	private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
		if (query.isEmpty() || this.isEmpty()) {
			return this
		}
		return filter { it.contains(query) }
	}

	private fun List<ChapterListItem>.filterReadBeforeCurrent(): List<ChapterListItem> {
		val currentIndex = indexOfFirst { it.isCurrent }
		if (currentIndex <= 0) {
			return this
		}
		return filterIndexed { index, item ->
			index >= currentIndex || item.isUnread || item.isCurrent
		}
	}



	protected open suspend fun onDownloadComplete(downloadedContent: LocalContent?) {
		downloadedContent ?: return
		mangaDetails.update {
			interactor.updateLocal(it, downloadedContent)
		}
	}

	class ActivityVMLazy(
		private val fragment: Fragment,
	) : Lazy<ChaptersPagesViewModel> {
		private var cached: ChaptersPagesViewModel? = null

		override val value: ChaptersPagesViewModel
			get() {
				val viewModel = cached
				return if (viewModel == null) {
					val activity = fragment.requireActivity()
					val vmClass = getViewModelClass(activity)
					ViewModelProvider.create(
						store = activity.viewModelStore,
						factory = activity.defaultViewModelProviderFactory,
						extras = activity.defaultViewModelCreationExtras,
					)[vmClass].also { cached = it }
				} else {
					viewModel
				}
			}

		override fun isInitialized(): Boolean = cached != null

		private fun getViewModelClass(activity: Activity) = when (activity) {
			is ReaderActivity -> ReaderViewModel::class.java
			is DetailsActivity -> DetailsViewModel::class.java
			is VideoPlayerActivity -> VideoChaptersViewModel::class.java
			else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
		}
	}
}

internal fun List<ChapterListItem>.mergeRepeated(): List<ChapterListItem> {
	if (this.isEmpty()) return this
	val groups = this.groupBy { item ->
		val ch = item.chapter
		if (ch.number > 0f) {
			val volKey = if (ch.volume > 0) ch.volume else null
			"num_${volKey}_${ch.number}"
		} else {
			val titleKey = ch.title?.lowercase()?.trim()
			if (!titleKey.isNullOrBlank()) {
				"title_$titleKey"
			} else {
				"unique_${ch.id}_${ch.url}"
			}
		}
	}
	return groups.map { (_, groupList) ->
		if (groupList.size == 1) {
			groupList.first()
		} else {
			groupList.maxWithOrNull(
				compareBy<ChapterListItem> { it.chapter.uploadDate }
					.thenBy { it.isDownloaded }
					.thenBy { it.chapter.scanlator?.isNotBlank() == true }
					.thenBy { it.chapter.title?.length ?: 0 }
			) ?: groupList.first()
		}
	}
}
