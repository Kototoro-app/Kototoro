package org.skepsun.kototoro.details.ui.pager.pages

import android.util.Log
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.reader.domain.ChaptersLoader
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import javax.inject.Inject

@HiltViewModel
class PagesViewModel @Inject constructor(
	private val chaptersLoader: ChaptersLoader,
	settings: AppSettings,
) : BaseViewModel() {

	private var loadingJob: Job? = null
	private var loadingPrevJob: Job? = null
	private var loadingNextJob: Job? = null
	private var targetChapterJob: Job? = null
	private var pendingTargetChapterId: Long? = null

	private val state = MutableStateFlow<State?>(null)
	val thumbnails = MutableStateFlow<List<ListModel>>(emptyList())
	val isLoadingUp = MutableStateFlow(false)
	val isLoadingDown = MutableStateFlow(false)
	val onPageSaved = MutableEventFlow<Collection<Uri>>()

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	val fitPreview = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_PAGE_THUMBNAILS_FIT_PREVIEW,
		valueProducer = { isPageThumbnailsFitPreview },
	)

	init {
		launchJob(Dispatchers.Default) {
			state.filterNotNull()
				.collect {
					val prevJob = loadingJob
					loadingJob = launchLoadingJob(Dispatchers.Default) {
						prevJob?.cancelAndJoin()
						doInit(it)
					}
				}
		}
	}

	fun updateState(newState: State?) {
		if (newState != null) {
			state.value = newState
		}
	}

	fun loadPrevChapter() {
		if (loadingJob?.isActive == true || loadingPrevJob?.isActive == true) {
			return
		}
		loadingPrevJob = loadPrevNextChapter(isNext = false)
	}

	fun loadNextChapter() {
		if (loadingJob?.isActive == true || loadingNextJob?.isActive == true) {
			return
		}
		loadingNextJob = loadPrevNextChapter(isNext = true)
	}

	fun loadTowardsChapter(chapterId: Long) {
		if (chaptersLoader.hasPages(chapterId)) {
			return
		}
		pendingTargetChapterId = chapterId
		if (targetChapterJob?.isActive == true) {
			return
		}
		targetChapterJob = launchJob(Dispatchers.Default) {
			loadingJob?.join()
			while (true) {
				loadingPrevJob?.join()
				loadingNextJob?.join()
				val targetId = pendingTargetChapterId ?: break
				if (chaptersLoader.hasPages(targetId)) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					continue
				}
				val currentState = state.value ?: break
				val details = currentState.details.filterChapters(currentState.branch)
				Log.d(
					"PagesViewModel",
					"loadTowardsChapter: targetId=$targetId, branch=${currentState.branch}, filteredCount=${details.allChapters.size}, available=${details.allChapters.take(3).map { "${it.id}|${it.branch}|${it.title}" }}",
				)
				val direction = resolveLoadDirection(details, targetId) ?: run {
					Log.d(
						"PagesViewModel",
						"loadTowardsChapter: cannot resolve direction for targetId=$targetId, branch=${currentState.branch}, filteredCount=${details.allChapters.size}",
					)
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					break
				}
				if (!loadOneChapter(details, currentState.readerState, direction)) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					break
				}
			}
		}
	}

	fun savePages(
		pageSaveHelper: PageSaveHelper,
		pages: Set<ReaderPage>,
	) {
		launchLoadingJob(Dispatchers.Default) {
			val manga = state.requireValue().details.toContent()
			val tasks = pages.map {
				PageSaveHelper.Task(
					manga = manga,
					chapterId = it.chapterId,
					pageNumber = it.index + 1,
					page = it.toContentPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			onPageSaved.call(dest)
		}
	}

	private suspend fun doInit(state: State) {
		val details = state.details.filterChapters(state.branch)
		Log.d(
			"PagesViewModel",
			"doInit: branch=${state.branch}, filteredCount=${details.allChapters.size}, first=${details.allChapters.firstOrNull()?.let { "${it.id}|${it.branch}|${it.title}" }}, readerChapter=${state.readerState?.chapterId}",
		)
		chaptersLoader.init(details)
		val initialChapterId = state.readerState?.chapterId?.takeIf { chaptersLoader.peekChapter(it) != null }
			?: details.allChapters.firstOrNull()?.id
			?: return
		Log.d("PagesViewModel", "doInit: initialChapterId=$initialChapterId")
		chaptersLoader.loadSingleChapter(initialChapterId)
		chaptersLoader.loadLocalChapters()
		updateList(state.readerState)
	}

	private fun loadPrevNextChapter(isNext: Boolean): Job = launchJob(Dispatchers.Default) {
		loadingJob?.join()
		val currentState = state.value ?: return@launchJob
		val details = currentState.details.filterChapters(currentState.branch)
		Log.d(
			"PagesViewModel",
			"loadPrevNextChapter: isNext=$isNext, branch=${currentState.branch}, filteredCount=${details.allChapters.size}",
		)
		loadOneChapter(details, currentState.readerState, isNext)
	}

	private suspend fun loadOneChapter(
		details: ContentDetails,
		readerState: ReaderState?,
		isNext: Boolean,
	): Boolean {
		val indicator = if (isNext) isLoadingDown else isLoadingUp
		indicator.value = true
		try {
			if (chaptersLoader.snapshot().isEmpty()) {
				return false
			}
			val currentId = if (isNext) chaptersLoader.last().chapterId else chaptersLoader.first().chapterId
			val loaded = chaptersLoader.loadPrevNextChapter(details, currentId, isNext)
			if (loaded) {
				updateList(readerState)
			}
			return loaded
		} finally {
			indicator.value = false
		}
	}

	private fun resolveLoadDirection(details: ContentDetails, targetChapterId: Long): Boolean? {
		if (chaptersLoader.snapshot().isEmpty()) {
			Log.d("PagesViewModel", "resolveLoadDirection: loader snapshot empty, targetId=$targetChapterId")
			return null
		}
		val targetIndex = details.allChapters.indexOfFirst { it.id == targetChapterId }
		if (targetIndex < 0) {
			Log.d(
				"PagesViewModel",
				"resolveLoadDirection: target not found, targetId=$targetChapterId, filteredCount=${details.allChapters.size}, first=${details.allChapters.take(3).map { "${it.id}|${it.branch}|${it.title}" }}",
			)
			return null
		}
		val firstIndex = details.allChapters.indexOfFirst { it.id == chaptersLoader.first().chapterId }
		val lastIndex = details.allChapters.indexOfFirst { it.id == chaptersLoader.last().chapterId }
		Log.d(
			"PagesViewModel",
			"resolveLoadDirection: targetId=$targetChapterId, targetIndex=$targetIndex, firstIndex=$firstIndex, lastIndex=$lastIndex, filteredCount=${details.allChapters.size}",
		)
		return when {
			lastIndex >= 0 && targetIndex > lastIndex -> true
			firstIndex >= 0 && targetIndex < firstIndex -> false
			else -> null
		}
	}

	private fun updateList(readerState: ReaderState?) {
		val currentState = state.value
		thumbnails.value = chaptersLoader.buildPageThumbnailList(
			readerState = readerState,
			chapters = currentState?.details?.filterChapters(currentState.branch)?.allChapters,
		)
	}

	data class State(
		val details: ContentDetails,
		val readerState: ReaderState?,
		val branch: String?
	)
}
