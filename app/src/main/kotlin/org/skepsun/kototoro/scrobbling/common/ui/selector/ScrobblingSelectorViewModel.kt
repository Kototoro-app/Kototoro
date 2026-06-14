package org.skepsun.kototoro.scrobbling.common.ui.selector

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.NO_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.common.ui.selector.model.ScrobblerHint
import javax.inject.Inject

@HiltViewModel
class ScrobblingSelectorViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val contentDataRepository: ContentDataRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: org.skepsun.kototoro.favourites.domain.FavouritesRepository,
	@dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : BaseViewModel() {

	private val initialManga = savedStateHandle.get<ParcelableContent>(AppRouter.KEY_MANGA)?.manga
	private var mangaState: Content? = initialManga
	private var initializedMangaId: Long? = null

	val manga: Content
		get() = checkNotNull(mangaState) {
			"ScrobblingSelectorViewModel is not initialized with a manga"
		}

	val availableScrobblers = scrobblers.sortedBy { it.scrobblerService.id }

	val selectedScrobblerIndex = MutableStateFlow(0)

	private val scrobblerContentList = MutableStateFlow<List<ScrobblerContent>>(emptyList())
	private val hasNextPage = MutableStateFlow(true)
	private val listError = MutableStateFlow<Throwable?>(null)
	private var loadingJob: Job? = null
	private var doneJob: Job? = null
	private var initJob: Job? = null

	private val currentScrobbler: Scrobbler
		get() = availableScrobblers[selectedScrobblerIndex.requireValue()]

	val content: StateFlow<List<ListModel>> = combine(
		scrobblerContentList,
		listError,
		hasNextPage,
	) { list, error, isHasNextPage ->
		if (list.isNotEmpty()) {
			if (isHasNextPage) {
				list + LoadingFooter()
			} else {
				list
			}
		} else {
			listOf(
				when {
					error != null -> errorHint(error)
					isHasNextPage -> LoadingFooter()
					else -> emptyResultsHint()
				},
			)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val selectedItemId = MutableStateFlow(NO_ID)
	val onClose = MutableEventFlow<Unit>()
	private val searchQuery = MutableStateFlow(initialManga?.title.orEmpty())

	val isEmpty: Boolean
		get() = scrobblerContentList.value.isEmpty()

	init {
		launchJob(Dispatchers.Default) {
			val resolved = resolveCurrentContent()
			if (resolved != null) {
				initialize(resolved)
			}
		}
	}

	fun initialize(manga: Content) {
		val isSameManga = initializedMangaId == manga.id
		mangaState = manga
		if (searchQuery.value.isBlank() || !isSameManga) {
			searchQuery.value = manga.title
		}
		if (isSameManga) {
			return
		}
		initializedMangaId = manga.id
		selectedItemId.value = NO_ID
		initialize()
	}

	fun search(query: String) {
		loadingJob?.cancel()
		searchQuery.value = query
		loadList(append = false)
	}

	fun selectItem(id: Long) {
		if (doneJob?.isActive == true) {
			return
		}
		selectedItemId.value = id
	}

	fun loadNextPage() {
		if (scrobblerContentList.value.isNotEmpty() && hasNextPage.value) {
			loadList(append = true)
		}
	}

	fun retry() {
		loadingJob?.cancel()
		hasNextPage.value = true
		scrobblerContentList.value = emptyList()
		loadList(append = false)
	}

	private fun loadList(append: Boolean) {
		if (loadingJob?.isActive == true) {
			return
		}
		loadingJob = launchJob(Dispatchers.Default) {
			listError.value = null
			val offset = if (append) scrobblerContentList.value.size else 0
			runCatchingCancellable {
				val isAnime = manga.source.getContentType().let { it == org.skepsun.kototoro.parsers.model.ContentType.VIDEO || it == org.skepsun.kototoro.parsers.model.ContentType.HENTAI_VIDEO } || (manga.url.startsWith("file://") && (manga.url.contains("/video/") || arrayOf(".mp4", ".mkv", ".webm", ".ts", ".avi", ".m3u8").any { manga.url.endsWith(it, ignoreCase = true) }))
				currentScrobbler.findContent(checkNotNull(searchQuery.value), offset, isAnime)
			}.onSuccess { list ->
				val newList = (if (append) {
					scrobblerContentList.value + list
				} else {
					list
				}).distinctBy { x -> x.id }
				val changed = newList != scrobblerContentList.value
				scrobblerContentList.value = newList
				hasNextPage.value = changed && newList.isNotEmpty()
			}.onFailure { error ->
				error.printStackTraceDebug()
				hasNextPage.value = false
				listError.value = error
			}
		}
	}

	fun onDoneClick() {
		if (doneJob?.isActive == true) {
			return
		}
		val targetId = selectedItemId.value
		if (targetId == NO_ID) {
			onClose.call(Unit)
			return
		}
		val selectedContent = scrobblerContentList.value.firstOrNull { it.id == targetId }
		checkNotNull(selectedContent) { "Selected scrobbler content $targetId not found" }
		if (!currentScrobbler.isEnabled) {
			errorEvent.call(ScrobblerAuthRequiredException(currentScrobbler.scrobblerService))
			return
		}
		doneJob = launchLoadingJob(Dispatchers.Default) {
			val prevInfo = currentScrobbler.getScrobblingInfoOrNull(manga.id)
			currentScrobbler.linkContent(manga.id, selectedContent)
			var linkedInfo = currentScrobbler.getScrobblingInfoOrNull(manga.id)
			if (linkedInfo == null) {
				currentScrobbler.syncLibrary()
				linkedInfo = currentScrobbler.getScrobblingInfoOrNull(manga.id)
			}
			checkNotNull(linkedInfo) {
				"Scrobbling info for manga ${manga.id} not found after linking target $targetId"
			}
			val history = historyRepository.getOne(manga)
			currentScrobbler.updateScrobblingInfo(
				mangaId = manga.id,
				rating = prevInfo?.rating ?: 0f,
				status = prevInfo?.status ?: when {
					history == null -> ScrobblingStatus.PLANNED
					ReadingProgress.isCompleted(history.percent) -> ScrobblingStatus.COMPLETED
					else -> ScrobblingStatus.READING
				},
				comment = prevInfo?.comment,
			)
			if (history != null && manga.chapters?.any { it.id == history.chapterId } == true) {
				currentScrobbler.scrobble(
					manga = manga,
					chapterId = history.chapterId,
				)
			}
			if (favouritesRepository.getCategoriesIdsByWork(manga.id).isEmpty()) {
				val categories = favouritesRepository.observeCategories().firstOrNull()
				val categoryId = if (!categories.isNullOrEmpty()) {
					categories.first().id
				} else {
					val name = context.getString(org.skepsun.kototoro.R.string.favourites)
					favouritesRepository.createCategory(name, org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST, false, true).id
				}
				favouritesRepository.addToCategory(categoryId, listOf(manga))
			}
			onClose.call(Unit)
		}
	}

	private suspend fun resolveCurrentContent(): Content? {
		val resolved = initialManga?.id
			?.takeIf { it != 0L }
			?.let {
				contentDataRepository.findPreferredLocalContentById(it, withChapters = true)
					?: contentDataRepository.findContentById(it, withChapters = true)
			}
		if (resolved != null) {
			return resolved
		}
		// This sheet uses KEY_ID for scrobbler service id, not content id.
		// Without an explicit local content anchor, missing DB state must still
		// fall back to the navigation seed for legacy entry compatibility.
		return initialManga
	}

	fun setScrobblerIndex(index: Int) {
		if (index == selectedScrobblerIndex.value || index !in availableScrobblers.indices) return
		selectedScrobblerIndex.value = index
		initialize()
	}

	fun isScrobblerAuthorized(index: Int): Boolean {
		return availableScrobblers.getOrNull(index)?.isEnabled == true
	}

	private fun initialize() {
		initJob?.cancel()
		loadingJob?.cancel()
		hasNextPage.value = true
		scrobblerContentList.value = emptyList()
		initJob = launchJob(Dispatchers.Default) {
			try {
				val info = currentScrobbler.getScrobblingInfoOrNull(manga.id)
				if (info != null) {
					selectedItemId.value = info.targetId
				}
			} finally {
				loadList(append = false)
			}
		}
	}

	private fun emptyResultsHint() = ScrobblerHint(
		icon = R.drawable.ic_empty_history,
		textPrimary = R.string.nothing_found,
		textSecondary = R.string.text_search_holder_secondary,
		error = null,
		actionStringRes = R.string.search,
	)

	private fun errorHint(e: Throwable): ScrobblerHint {
		val resolveAction = ExceptionResolver.getResolveStringId(e)
		return ScrobblerHint(
			icon = R.drawable.ic_error_large,
			textPrimary = R.string.error_occurred,
			error = e,
			textSecondary = if (resolveAction == 0) 0 else R.string.try_again,
			actionStringRes = resolveAction.ifZero { R.string.try_again },
		)
	}
}
