package org.skepsun.kototoro.download.ui.dialog

import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import androidx.collection.MutableLongLongMap
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.sizeOrZero
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy
import org.skepsun.kototoro.settings.storage.DirectoryModel
import org.skepsun.kototoro.video.domain.resolveVideoCandidates
import javax.inject.Inject

@HiltViewModel
class DownloadDialogViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val scheduler: DownloadWorker.Scheduler,
	private val localStorageManager: LocalStorageManager,
	private val localContentRepository: LocalMangaRepository,
	private val contentDataRepository: ContentDataRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val initialManga = savedStateHandle.get<Array<ParcelableContent>>(AppRouter.KEY_MANGA)
		?.map { it.manga }
		.orEmpty()
	private val initialMangaIds = savedStateHandle.get<LongArray>(AppRouter.KEY_ID)?.toList().orEmpty()

	var manga: List<Content> = emptyList()
		private set

	private val mangaDetails = suspendLazy {
		coroutineScope {
			manga.map { m ->
				async { m.getDetails() }
			}.awaitAll()
		}
	}
	val onScheduled = MutableEventFlow<Boolean>()
	val defaultFormat = MutableStateFlow<DownloadFormat?>(null)
	val availableDestinations = MutableStateFlow(listOf(defaultDestination()))
	val chaptersSelectOptions = MutableStateFlow(
		ChapterSelectOptions(
			wholeContent = ChaptersSelectMacro.WholeContent(0),
			wholeBranch = null,
			firstChapters = null,
			unreadChapters = null,
		),
	)
	val isOptionsLoading = MutableStateFlow(true)
	val videoQualities = MutableStateFlow<List<String>?>(null)
	val isVideoContent = MutableStateFlow(false)
	val isVideoQualitiesLoading = MutableStateFlow(false)

	private var isInitialized = false

	init {
		launchJob(Dispatchers.Default) {
			if (initialMangaIds.isEmpty()) return@launchJob
			val resolved = initialMangaIds.mapNotNull { id ->
				contentDataRepository.findDisplayContentById(id, withChapters = false)
					?: initialManga.firstOrNull { it.id == id }
			}
			if (resolved.isNotEmpty()) {
				initialize(resolved)
			}
		}
	}

	fun initialize(mangaList: List<Content>) {
		if (isInitialized || mangaList.isEmpty()) return
		isInitialized = true
		this.manga = mangaList

		launchJob(Dispatchers.Default) {
			defaultFormat.value = settings.preferredDownloadFormat
		}
		launchJob(Dispatchers.Default) {
			try {
				loadAvailableOptions()
			} finally {
				isOptionsLoading.value = false
			}
		}
		launchJob(Dispatchers.Default) {
			try {
				val firstManga = mangaDetails.get().firstOrNull()
				if (firstManga?.source?.getContentType() == ContentType.VIDEO) {
					isVideoContent.value = true
					isVideoQualitiesLoading.value = true
					loadAvailableVideoQualities()
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			} finally {
				isVideoQualitiesLoading.value = false
			}
		}
		loadAvailableDestinations()
	}

	fun confirm(
		startNow: Boolean,
		chaptersMacro: ChaptersSelectMacro,
		format: DownloadFormat?,
		destination: DirectoryModel?,
		allowMetered: Boolean,
		preferredQuality: String? = null,
	) {
		launchLoadingJob(Dispatchers.Default) {
			val tasks = mangaDetails.get().map { m ->
				val chapters = checkNotNull(m.chapters) { "Content \"${m.title}\" cannot be loaded" }
				val selectedChapterIds = chaptersMacro.getChaptersIds(m.id, chapters)
				val selectedChapterRefs = selectedChapterIds
					?.mapNotNull { chapterId -> chapters.firstOrNull { it.id == chapterId }?.let(ExecutionChapterRef::fromChapter) }
				m to DownloadTask.createExecutionTask(
					executionMangaId = m.id,
					displayMangaId = m.id,
					isPaused = !startNow,
					isSilent = false,
					executionChapterIds = selectedChapterIds?.toLongArray(),
					executionChapterRefs = selectedChapterRefs,
					destination = destination?.file,
					format = format,
					allowMeteredNetwork = allowMetered,
					preferredQuality = preferredQuality,
				)
			}
			scheduler.schedule(tasks)
			onScheduled.call(startNow)
		}
	}

	fun setSelectedBranch(branch: String?) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			wholeBranch = snapshot.wholeBranch?.copy(branch),
		)
	}

	fun setFirstChaptersCount(count: Int) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			firstChapters = snapshot.firstChapters?.copy(count),
		)
	}

	fun setUnreadChaptersCount(count: Int) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			unreadChapters = snapshot.unreadChapters?.copy(count),
		)
	}

	fun getChapterDownloadDelay(): Int = settings.downloadChapterDelay

	fun setChapterDownloadDelay(seconds: Int) {
		settings.downloadChapterDelay = seconds
	}

	fun isDownloadAlignedWithReader(): Boolean = settings.isDownloadAlignedWithReader

	fun setDownloadAlignedWithReader(enabled: Boolean) {
		settings.isDownloadAlignedWithReader = enabled
	}

	fun getDownloadThreads(): Int = settings.downloadThreads

	fun setDownloadThreads(value: Int) {
		settings.downloadThreads = value
	}

	fun getDownloadRequestDelayMs(): Int = settings.downloadRequestDelayMs

	fun setDownloadRequestDelayMs(value: Int) {
		settings.downloadRequestDelayMs = value
	}

	fun getDownloadRetryCount(): Int = settings.downloadRetryCount

	fun setDownloadRetryCount(value: Int) {
		settings.downloadRetryCount = value
	}

	fun getDownloadRetryDelayMs(): Int = settings.downloadRetryDelayMs

	fun setDownloadRetryDelayMs(value: Int) {
		settings.downloadRetryDelayMs = value
	}

	fun isDownloadAutoRetryEnabled(): Boolean = settings.isDownloadAutoRetryOnNetworkError

	fun setDownloadAutoRetryEnabled(enabled: Boolean) {
		settings.isDownloadAutoRetryOnNetworkError = enabled
	}

	private fun defaultDestination() = DirectoryModel(
		title = null,
		titleRes = R.string.system_default,
		file = null,
		isRemovable = false,
		isChecked = true,
		isAvailable = true,
	)

	private suspend fun loadAvailableOptions() {
		val details = mangaDetails.get()
		var totalChapters = 0
		val branches = ArrayMap<String?, Int>()
		var maxChapters = 0
		var maxUnreadChapters = 0
		val preferredBranches = ArraySet<String?>(details.size)
		val currentChaptersIds = MutableLongLongMap(details.size)

		details.forEach { m ->
			val history = historyRepository.getOne(m)
			if (history != null) {
				val historyChapter = m.chapters?.firstOrNull { it.id == history.chapterId }
				if (historyChapter != null) {
					currentChaptersIds[m.id] = historyChapter.id
				}
				val unreadChaptersCount = if (historyChapter != null) {
					m.chapters?.dropWhile { it.id != historyChapter.id }.sizeOrZero()
				} else {
					m.chapters.sizeOrZero()
				}
				maxUnreadChapters = maxOf(maxUnreadChapters, unreadChaptersCount)
			} else {
				maxUnreadChapters = maxOf(maxUnreadChapters, m.chapters.sizeOrZero())
			}
			maxChapters = maxOf(maxChapters, m.chapters.sizeOrZero())
			preferredBranches.add(m.getPreferredBranch(history))
			m.chapters?.forEach { c ->
				totalChapters++
				branches.increment(c.branch)
			}
		}
		val defaultBranch = preferredBranches.firstOrNull()
		chaptersSelectOptions.value = ChapterSelectOptions(
			wholeContent = ChaptersSelectMacro.WholeContent(totalChapters),
			wholeBranch = if (branches.size > 1) {
				ChaptersSelectMacro.WholeBranch(
					branches = branches,
					selectedBranch = defaultBranch,
				)
			} else {
				null
			},
			firstChapters = if (maxChapters > 0) {
				ChaptersSelectMacro.FirstChapters(
					chaptersCount = minOf(5, maxChapters),
					maxAvailableCount = maxChapters,
					branch = defaultBranch,
				)
			} else {
				null
			},
			unreadChapters = if (currentChaptersIds.isNotEmpty()) {
				ChaptersSelectMacro.UnreadChapters(
					chaptersCount = minOf(5, maxUnreadChapters),
					maxAvailableCount = maxUnreadChapters,
					currentChaptersIds = currentChaptersIds,
				)
			} else {
				null
			},
		)
	}

	private fun loadAvailableDestinations() = launchJob(Dispatchers.Default) {
		val defaultDir = manga.mapToSet {
			localContentRepository.getOutputDir(it, null)
		}.singleOrNull()
		
		val isVideo = manga.firstOrNull()?.source?.getContentType() == ContentType.VIDEO
		val isNovel = manga.firstOrNull()?.source?.getContentType() == ContentType.NOVEL
		
		val dirs = when {
			isVideo -> localStorageManager.getVideoWriteableDirs()
			isNovel -> localStorageManager.getNovelWriteableDirs()
			else -> localStorageManager.getWriteableDirs()
		}
		availableDestinations.value = buildList(dirs.size + 1) {
			if (defaultDir == null) {
				add(defaultDestination())
			} else if (defaultDir !in dirs) {
				add(
					DirectoryModel(
						title = localStorageManager.getDirectoryDisplayName(defaultDir, isFullPath = false),
						titleRes = 0,
						file = defaultDir,
						isChecked = true,
						isAvailable = true,
						isRemovable = false,
					),
				)
			}
			dirs.mapTo(this) { dir ->
				DirectoryModel(
					title = localStorageManager.getDirectoryDisplayName(dir, isFullPath = false),
					titleRes = 0,
					file = dir,
					isChecked = dir == defaultDir,
					isAvailable = true,
					isRemovable = false,
				)
			}
		}
	}

	private suspend fun loadAvailableVideoQualities() {
		val firstManga = mangaDetails.get().firstOrNull() ?: return
		val firstChapter = firstManga.chapters?.firstOrNull() ?: return
		val repo = mangaRepositoryFactory.create(firstManga.source)
		
		runCatchingCancellable {
			val qualities = repo.resolveVideoCandidates(firstChapter)
				.map { it.title.trim() }
				.filter { it.isNotEmpty() }
				.distinct()
			if (qualities.isNotEmpty()) {
				videoQualities.value = qualities
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private suspend fun Content.getDetails(): Content = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrDefault(this)

	private fun <T> MutableMap<T, Int>.increment(key: T) {
		put(key, getOrDefault(key, 0) + 1)
	}
}
