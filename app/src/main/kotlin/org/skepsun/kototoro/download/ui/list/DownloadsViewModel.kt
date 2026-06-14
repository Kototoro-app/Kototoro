package org.skepsun.kototoro.download.ui.list

import androidx.collection.ArrayMap
import androidx.collection.LongSet
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.collection.set
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.isEmpty
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.download.domain.DownloadState
import org.skepsun.kototoro.download.ui.list.chapters.DownloadChapter
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.LinkedList
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
	private val workScheduler: DownloadWorker.Scheduler,
	private val mangaDataRepository: ContentDataRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
	private val localContentRepository: LocalMangaRepository,
) : BaseViewModel() {

	private val mangaCache = LongSparseArray<Content>()
	private val cacheMutex = Mutex()
	private val expanded = MutableStateFlow(emptySet<UUID>())
	private val chaptersCache = ArrayMap<UUID, StateFlow<List<DownloadChapter>?>>()

	private val works = combine(
		workScheduler.observeWorks(),
		expanded,
	) { list, exp ->
		list.toDownloadsList(exp)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val items = works.map {
		it?.toUiList() ?: listOf(LoadingState)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val hasPausedWorks = works.map {
		it?.any { x -> x.canResume } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasActiveWorks = works.map {
		it?.any { x -> x.canPause } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasCancellableWorks = works.map {
		it?.any { x -> !x.workState.isFinished } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	fun cancel(id: UUID) {
		launchJob(Dispatchers.Default) {
			workScheduler.cancel(id)
		}
	}

	fun cancel(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val snapshot = works.value ?: return@launchJob
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					workScheduler.cancel(work.id)
				}
			}
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun cancelAll() {
		launchJob(Dispatchers.Default) {
			workScheduler.cancelAll()
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun pause(ids: Set<Long>) {
		val snapshot = works.value ?: return
		for (work in snapshot) {
			if (work.id.mostSignificantBits in ids) {
				workScheduler.pause(work.id)
			}
		}
		onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
	}

	fun pauseAll() {
		val snapshot = works.value ?: return
		var isPaused = false
		for (work in snapshot) {
			if (work.canPause) {
				workScheduler.pause(work.id)
				isPaused = true
			}
		}
		if (isPaused) {
			onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
		}
	}

	fun resumeAll() {
		val snapshot = works.value ?: return
		launchJob(Dispatchers.Default) {
			var isResumed = false
			for (work in snapshot) {
				if (work.workState == WorkInfo.State.RUNNING && work.isPaused) {
					workScheduler.resume(work.id)
					isResumed = true
				} else if (work.workState == WorkInfo.State.FAILED) {
					isResumed = retryWork(work) || isResumed
				}
			}
			if (isResumed) {
				onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
			}
		}
	}

	fun resume(ids: Set<Long>) {
		val snapshot = works.value ?: return
		launchJob(Dispatchers.Default) {
			var isResumed = false
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					if (work.workState == WorkInfo.State.RUNNING && work.isPaused) {
						workScheduler.resume(work.id)
						isResumed = true
					} else if (work.workState == WorkInfo.State.FAILED) {
						isResumed = retryWork(work) || isResumed
					}
				}
			}
			if (isResumed) {
				onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
			}
		}
	}

	fun remove(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val snapshot = works.value ?: return@launchJob
			val uuids = HashSet<UUID>(ids.size)
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					uuids.add(work.id)
				}
			}
			workScheduler.delete(uuids)
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun removeCompleted() {
		launchJob(Dispatchers.Default) {
			workScheduler.removeCompleted()
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun snapshot(ids: LongSet): Collection<DownloadItemModel> {
		return works.value?.filterTo(ArrayList(ids.size)) { x -> x.id.mostSignificantBits in ids }.orEmpty()
	}

	fun allIds(): Set<Long> = works.value?.mapToSet {
		it.id.mostSignificantBits
	} ?: emptySet()

	fun expandCollapse(item: DownloadItemModel) {
		expanded.update {
			if (item.id in it) {
				it - item.id
			} else {
				it + item.id
			}
		}
	}

	private suspend fun List<WorkInfo>.toDownloadsList(exp: Set<UUID>): List<DownloadItemModel> {
		if (isEmpty()) {
			return emptyList()
		}
		val list = mapNotNullTo(ArrayList(size)) { it.toUiModel(it.id in exp) }
		list.sortByDescending { it.timestamp }
		return list
	}

	private fun List<DownloadItemModel>.toUiList(): List<ListModel> {
		if (isEmpty()) {
			return emptyStateList()
		}
		val queued = LinkedList<ListModel>()
		val running = LinkedList<ListModel>()
		val destination = ArrayDeque<ListModel>((size * 1.4).toInt())
		var prevDate: DateTimeAgo? = null
		for (item in this) {
			when (item.workState) {
				WorkInfo.State.RUNNING -> running += item
				WorkInfo.State.BLOCKED,
				WorkInfo.State.ENQUEUED -> queued += item

				else -> {
					val date = calculateTimeAgo(item.timestamp)
					if (prevDate != date) {
						destination += if (date != null) {
							ListHeader(date)
						} else {
							ListHeader(R.string.unknown)
						}
					}
					prevDate = date
					destination += item
				}
			}
		}
		if (running.isNotEmpty()) {
			running.addFirst(ListHeader(R.string.in_progress))
		}
		destination.addAll(0, running)
		if (queued.isNotEmpty()) {
			queued.addFirst(ListHeader(R.string.queued))
		}
		destination.addAll(0, queued)
		return destination
	}

	private suspend fun WorkInfo.toUiModel(isExpanded: Boolean): DownloadItemModel? {
		val workData = outputData.takeUnless { it.isEmpty }
			?: progress.takeUnless { it.isEmpty }
			?: workScheduler.getInputData(id)
			?: return null
		val mangaId = DownloadState.getExecutionContentId(workData)
		if (mangaId == 0L) return null
		val executionManga = getContent(mangaId) ?: return null
		val displayMangaId = DownloadState.getDisplayContentId(workData)
		val displayManga = displayMangaId
			?.let { getDisplayContent(it) ?: getContent(it) }
			?: getDisplayContent(mangaId)
			?: executionManga
		val chapters = synchronized(chaptersCache) {
			chaptersCache.getOrPut(id) {
				observeChapters(executionManga, displayManga, id)
			}
		}
		return DownloadItemModel(
			id = id,
			workState = state,
			executionManga = executionManga,
			displayManga = displayManga,
			taskKind = DownloadState.getTaskKind(workData),
			error = DownloadState.getError(workData),
			isIndeterminate = DownloadState.isIndeterminate(workData),
			isPaused = DownloadState.isPaused(workData),
			max = DownloadState.getMax(workData),
			progress = DownloadState.getProgress(workData),
			eta = DownloadState.getEta(workData),
			isStuck = DownloadState.isStuck(workData),
			timestamp = DownloadState.getTimestamp(workData),
			chaptersDownloaded = DownloadState.getDownloadedChapters(workData),
			isExpanded = isExpanded,
			chapters = chapters,
		)
	}

	private fun emptyStateList() = listOf(
		EmptyState(
			icon = R.drawable.ic_empty_common,
			textPrimary = R.string.text_downloads_list_holder,
			textSecondary = 0,
			actionStringRes = 0,
		),
	)

	private suspend fun getContent(mangaId: Long): Content? {
		mangaCache[mangaId]?.let {
			return it
		}
		return cacheMutex.withLock {
			mangaCache.getOrElse(mangaId) {
				mangaDataRepository.findContentById(mangaId, withChapters = true)?.also {
					mangaCache[mangaId] = it
				} ?: return null
			}
		}
	}

	private suspend fun getDisplayContent(mangaId: Long): Content? {
		val displayContent = mangaDataRepository.findDisplayContentById(mangaId, withChapters = false)
		if (displayContent != null) {
			mangaCache[displayContent.id] = displayContent
		}
		return displayContent
	}

	private fun observeChapters(
		executionManga: Content,
		displayManga: Content,
		workId: UUID,
	): StateFlow<List<DownloadChapter>?> = flow {
		val chapterIds = workScheduler.getTask(workId)?.executionChapterIds
		val chapters = (tryLoad(executionManga) ?: executionManga).chapters ?: return@flow
		val watchedLocalIds = buildSet {
			add(executionManga.id)
			add(displayManga.id)
		}

		suspend fun mapChapters(): List<DownloadChapter> {
			val size = chapterIds?.size ?: chapters.size
			val localCandidates = buildList {
				add(displayManga)
				if (displayManga.id != executionManga.id) {
					add(executionManga)
				}
			}
			val resolvedLocalChapters = buildList {
				for (candidate in localCandidates) {
					val localChapters = localContentRepository.findSavedContent(candidate)?.manga?.chapters
					if (localChapters?.any { localChapter -> localChapter.source.isLocal } == true) {
						addAll(localChapters.filter { localChapter -> localChapter.source.isLocal })
						break
					}
				}
			}
			val unmatchedLocalChapters = resolvedLocalChapters.toMutableList()
			return chapters.mapNotNullTo(ArrayList(size)) {
				if (chapterIds == null || it.id in chapterIds) {
					val matchedLocalChapter = unmatchedLocalChapters.find { localChapter ->
						localChapter.id == it.id
					} ?: unmatchedLocalChapters.find { localChapter ->
						localChapter.number == it.number &&
							localChapter.title == it.title &&
							(it.number > 0f || !it.title.isNullOrBlank())
					} ?: unmatchedLocalChapters.find { localChapter ->
						localChapter.number == it.number && it.number > 0f
					} ?: unmatchedLocalChapters.find { localChapter ->
						localChapter.title == it.title && !it.title.isNullOrBlank()
					}
					if (matchedLocalChapter != null) {
						unmatchedLocalChapters.remove(matchedLocalChapter)
					}
					DownloadChapter(
						number = it.numberString(),
						name = it.title?.takeIf { title -> title.isNotBlank() } ?: buildString {
							if (it.number > 0f) {
								append("Chapter ").append(it.numberString())
							} else {
								append("Unnamed")
							}
						},
						isDownloaded = matchedLocalChapter != null,
					)
				} else {
					null
				}
			}
		}
		emit(mapChapters())
		localStorageChanges.collect {
			if (it?.manga?.id in watchedLocalIds) {
				emit(mapChapters())
			}
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private suspend fun tryLoad(manga: Content) = runCatchingCancellable {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}.getOrNull()

	private suspend fun retryWork(work: DownloadItemModel): Boolean {
		val task = workScheduler.getTask(work.id) ?: return false
		val manga = work.executionManga ?: getContent(task.executionMangaId) ?: return false
		val displayMangaId = task.displayMangaId
			?.let { getDisplayContent(it)?.id ?: getContent(it)?.id }
			?: getDisplayContent(task.executionMangaId)?.id
			?: manga.id
		synchronized(chaptersCache) {
			chaptersCache.remove(work.id)
		}
		Log.i(
			"DownloadsViewModel",
			"retryWork: requeue workId=${work.id} mangaId=${task.executionMangaId} title=${manga.title}",
		)
		val newTask = DownloadTask.createExecutionTask(
			executionMangaId = task.executionMangaId,
			displayMangaId = displayMangaId,
			isPaused = false,
			isSilent = task.isSilent,
			executionChapterIds = task.executionChapterIds,
			executionChapterRefs = task.executionChapterRefs,
			destination = task.destination,
			format = task.format,
			allowMeteredNetwork = task.allowMeteredNetwork,
			preferredQuality = task.preferredQuality,
			kind = task.kind,
		)
		workScheduler.delete(work.id)
		workScheduler.schedule(listOf(manga to newTask))
		return true
	}
}
