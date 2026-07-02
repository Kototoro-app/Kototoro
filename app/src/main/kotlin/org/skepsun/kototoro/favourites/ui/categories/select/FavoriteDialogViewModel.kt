package org.skepsun.kototoro.favourites.ui.categories.select

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.ids
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.getOriginLabel
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.favourites.domain.MergeBackAndAddFavouriteUseCase
import org.skepsun.kototoro.favourites.ui.categories.select.model.ContentCategoryItem
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkDuplicateCandidate
import org.skepsun.kototoro.work.domain.WorkDuplicateCandidateRepository
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	private val contentDataRepository: ContentDataRepository,
	private val duplicateCandidateRepository: WorkDuplicateCandidateRepository,
	private val mergeBackAndAddFavouriteUseCase: MergeBackAndAddFavouriteUseCase,
	settings: AppSettings,
	@LocalizedAppContext private val context: Context,
) : BaseViewModel() {

	private val initialManga = savedStateHandle.get<List<ParcelableContent>>(AppRouter.KEY_MANGA_LIST)?.map { it.manga }.orEmpty()
	private val initialMangaIds = savedStateHandle.get<LongArray>(AppRouter.KEY_ID)?.toList().orEmpty()
	private val mangaState = MutableStateFlow(
		initialManga,
	)

	val manga: List<Content>
		get() = mangaState.value

	val duplicatePrompt = MutableStateFlow<FavoriteDuplicatePrompt?>(null)

	private val refreshTrigger = MutableStateFlow(Any())
	val content = mangaState.flatMapLatest { currentManga ->
		if (currentManga.isEmpty()) {
			flowOf(listOf(LoadingState))
		} else {
			combine(
				favouritesRepository.observeCategories(),
				refreshTrigger,
				settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
			) { categories, _, tracker ->
				mapList(currentManga, categories, tracker)
			}
		}
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			if (initialMangaIds.isEmpty()) return@launchJob
			val resolved = initialMangaIds.mapNotNull { id ->
				contentDataRepository.findContentById(id, withChapters = false)
					?: contentDataRepository.findPreferredLocalContentById(id, withChapters = false)
					?: initialManga.firstOrNull { it.id == id }
			}
			if (resolved.isNotEmpty()) {
				mangaState.value = resolved
			}
		}
	}

	fun initialize(manga: Collection<Content>) {
		if (manga.isEmpty() || mangaState.value == manga) {
			return
		}
		mangaState.value = manga.toList()
	}

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		val snapshot = mangaState.value
		if (snapshot.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			if (isChecked) {
				val candidates = snapshot
					.takeIf { it.size == 1 }
					?.firstOrNull()
					?.let { duplicateCandidateRepository.findCandidates(it) }
					.orEmpty()
				if (candidates.isNotEmpty()) {
					duplicatePrompt.value = FavoriteDuplicatePrompt(
						categoryId = categoryId,
						contentTitle = snapshot.first().title,
						candidates = candidates.take(MAX_DUPLICATE_PROMPT_CANDIDATES),
					)
					return@launchJob
				}
				favouritesRepository.addToCategory(categoryId, snapshot)
			} else {
				favouritesRepository.removeFromCategory(categoryId, snapshot.ids())
			}
			refreshTrigger.value = Any()
		}
	}

	fun confirmDuplicatePrompt() {
		val prompt = duplicatePrompt.value ?: return
		duplicatePrompt.value = null
		val snapshot = mangaState.value
		if (snapshot.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			favouritesRepository.addToCategoryAsSeparateWorks(prompt.categoryId, snapshot)
			refreshTrigger.value = Any()
		}
	}

	fun dismissDuplicatePrompt() {
		duplicatePrompt.value = null
	}

	fun mergeBackDuplicatePrompt() {
		val prompt = duplicatePrompt.value ?: return
		val targetEntityId = prompt.mergeBackTargetEntityId ?: return
		duplicatePrompt.value = null
		val content = mangaState.value.singleOrNull() ?: return
		launchJob(Dispatchers.Default) {
			mergeBackAndAddFavouriteUseCase(
				categoryId = prompt.categoryId,
				content = content,
				targetEntityId = targetEntityId,
			)
			refreshTrigger.value = Any()
		}
	}


	private suspend fun mapList(
		manga: List<Content>,
		categories: List<FavouriteCategory>,
		tracker: Boolean,
	): List<ListModel> {
		if (categories.isEmpty()) {
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.empty_favourite_categories,
					textSecondary = 0,
					actionStringRes = 0,
				),
			)
		}
		val cats = MutableLongObjectMap<MutableLongSet>(categories.size)
		categories.forEach { cats[it.id] = MutableLongSet(manga.size) }
		for (m in manga) {
			val ids = favouritesRepository.getCategoriesIdsByWork(m.id)
			ids.forEach { id -> cats[id]?.add(m.id) }
		}
		return categories.map { cat ->
			ContentCategoryItem(
				category = cat,
				checkedState = when (cats[cat.id]?.size ?: 0) {
					0 -> MaterialCheckBox.STATE_UNCHECKED
					manga.size -> MaterialCheckBox.STATE_CHECKED
					else -> MaterialCheckBox.STATE_INDETERMINATE
				},
				isTrackerEnabled = tracker,
			)
		}
	}

	private companion object {
		private const val MAX_DUPLICATE_PROMPT_CANDIDATES = 3
	}
}

data class FavoriteDuplicatePrompt(
	val categoryId: Long,
	val contentTitle: String,
	val candidates: List<WorkDuplicateCandidate>,
) {
	val mergeBackTargetEntityId: Long?
		get() = candidates.firstNotNullOfOrNull { it.mergeBackTargetEntityId }
}
