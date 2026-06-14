package org.skepsun.kototoro.picker.ui.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.details.ui.pager.pages.buildPageThumbnailList
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.reader.domain.ChaptersLoader
import javax.inject.Inject

@HiltViewModel
class PagePickerViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val chaptersLoader: ChaptersLoader,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	settings: AppSettings,
) : BaseViewModel() {

	private val intent = ContentIntent(savedStateHandle)
	private var loadingNextJob: Job? = null
	private var targetChapterJob: Job? = null
	private var pendingTargetChapterId: Long? = null

	val thumbnails = MutableStateFlow<List<ListModel>>(emptyList())
	val manga = MutableStateFlow<ContentDetails?>(null)

	val isNoChapters = manga.map {
		it != null && it.isLoaded && it.allChapters.isEmpty()
	}

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	init {
		launchLoadingJob(Dispatchers.Default) {
			doInit()
		}
	}

	private suspend fun doInit() {
		val details = detailsLoadUseCase.invoke(intent, force = false)
			.onEach { manga.value = it }
			.first { x -> x.isLoaded }
		chaptersLoader.init(details)
		val initialChapterId = details.allChapters.firstOrNull()?.id ?: return
		chaptersLoader.loadSingleChapter(initialChapterId)
		chaptersLoader.loadLocalChapters()
		updateList()
	}

	fun loadNextChapter() {
		if (isLoading.value || loadingNextJob?.isActive == true || chaptersLoader.snapshot().isEmpty()) {
			return
		}
		loadingNextJob = launchJob(Dispatchers.Default) {
			val details = manga.value ?: return@launchJob
			val currentId = chaptersLoader.last().chapterId
			chaptersLoader.loadPrevNextChapter(details, currentId, isNext = true)
			updateList()
		}
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
			while (true) {
				loadingNextJob?.join()
				val targetId = pendingTargetChapterId ?: break
				if (chaptersLoader.hasPages(targetId)) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					continue
				}
				val details = manga.value ?: break
				val targetIndex = details.allChapters.indexOfFirst { it.id == targetId }
				if (targetIndex < 0 || chaptersLoader.snapshot().isEmpty()) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					break
				}
				val lastIndex = details.allChapters.indexOfFirst { it.id == chaptersLoader.last().chapterId }
				if (lastIndex < 0 || targetIndex <= lastIndex) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					break
				}
				val currentId = chaptersLoader.last().chapterId
				if (!chaptersLoader.loadPrevNextChapter(details, currentId, isNext = true)) {
					if (pendingTargetChapterId == targetId) {
						pendingTargetChapterId = null
					}
					break
				}
				updateList()
			}
		}
	}

	private fun updateList() {
		thumbnails.value = chaptersLoader.buildPageThumbnailList(
			chapters = manga.value?.allChapters,
		)
	}
}
