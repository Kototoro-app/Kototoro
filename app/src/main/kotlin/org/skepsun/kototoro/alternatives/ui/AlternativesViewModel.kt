package org.skepsun.kototoro.alternatives.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.domain.AlternativesUseCase
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.append
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ButtonFooter
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@HiltViewModel
class AlternativesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val contentDataRepository: ContentDataRepository,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaListMapper: ContentListMapper,
) : BaseViewModel() {

	private val initialManga = savedStateHandle.get<ParcelableContent>(AppRouter.KEY_MANGA)?.manga
	private val intent = ContentIntent(savedStateHandle)
	private val mangaState = MutableStateFlow<Content?>(initialManga)
	val manga: Content
		get() = checkNotNull(mangaState.value) {
			"AlternativesViewModel is not initialized with content"
		}

	private var includeDisabledSources = MutableStateFlow(false)
	private var isPinnedOnly = MutableStateFlow(false)
	val pinnedOnly: StateFlow<Boolean> = isPinnedOnly
	
	val isPinnedOnlySelected: Boolean
		get() = isPinnedOnly.value

	private val results = MutableStateFlow<List<ContentAlternativeModel>>(emptyList())

	private var migrationJob: Job? = null
	private var searchJob: Job? = null

	val onMigrated = MutableEventFlow<Content>()

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading,
		includeDisabledSources,
		mangaState,
	) { list, loading, includeDisabled, manga ->
		when {
			manga == null -> listOf(LoadingState)
			list.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> list + LoadingFooter()
			includeDisabled -> list
			else -> list + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			val resolved = resolveCurrentContent()
			if (resolved != null) {
				initialize(resolved)
			}
		}
	}

	fun initialize(manga: Content) {
		if (mangaState.value?.id == manga.id) {
			return
		}
		searchJob?.cancel()
		migrationJob?.cancel()
		mangaState.value = manga
		results.value = emptyList()
		includeDisabledSources.value = false
		isPinnedOnly.value = false
		doSearch(throughDisabledSources = false)
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		includeDisabledSources.value = false
		doSearch(throughDisabledSources = false)
	}

	fun continueSearch() {
		if (includeDisabledSources.value) {
			return
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			includeDisabledSources.value = true
			prevJob?.join()
			doSearch(throughDisabledSources = true)
		}
	}

	fun migrate(target: Content) {
		if (migrationJob?.isActive == true) {
			return
		}
		migrationJob = launchLoadingJob(Dispatchers.Default) {
			migrateUseCase(manga, target)
			onMigrated.call(target)
		}
	}
	fun setPinnedOnly(pinnedOnly: Boolean) {
		if (isPinnedOnly.value == pinnedOnly) return
		isPinnedOnly.value = pinnedOnly
		searchJob?.cancel()
		results.value = emptyList()
		doSearch(throughDisabledSources = includeDisabledSources.value)
	}

	private fun doSearch(throughDisabledSources: Boolean) {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			val sourceManga = manga
			val ref = runCatching {
				mangaRepositoryFactory.create(sourceManga.source).getDetails(sourceManga)
			}.getOrDefault(sourceManga)
			val refCount = ref.chaptersCount()
			alternativesUseCase.invoke(ref, throughDisabledSources, pinnedOnly = isPinnedOnly.value)
				.collect {
					val model = ContentAlternativeModel(
						mangaModel = mangaListMapper.toListModel(it, ListMode.GRID) as ContentGridModel,
						referenceChapters = refCount,
					)
					results.append(model)
				}
		}
	}

	private suspend fun resolveCurrentContent(): Content? {
		val resolved = contentDataRepository.resolveIntent(intent, withChapters = true)
		if (resolved != null) {
			return resolved
		}
		return initialManga.takeIf { intent.mangaId == 0L }
	}
}
