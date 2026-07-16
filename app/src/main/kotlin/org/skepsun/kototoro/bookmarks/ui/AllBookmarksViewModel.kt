package org.skepsun.kototoro.bookmarks.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import javax.inject.Inject

@HiltViewModel
class AllBookmarksViewModel @Inject constructor(
	private val repository: BookmarksRepository,
	private val sourceGroupManager: SourceGroupManager,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
	settings: AppSettings,
	spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val gridScale: StateFlow<Float> = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE,
		valueProducer = { gridSize / 100f },
	)

	val currentGroupTab: StateFlow<BrowseGroupTab> = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)
	val currentSourceTags: StateFlow<Set<SourceTag>> = globalFavoritesState.selectedSourceTags

	val content: StateFlow<List<ListModel>> = combine(
		repository.observeBookmarks(),
		currentGroupTab,
		currentSourceTags,
	) { bookmarks, groupTab, sourceTags ->
		bookmarks.filterByTopBar(groupTab, sourceTags)
	}.map { filteredBookmarks ->
			if (filteredBookmarks.isEmpty()) {
				listOf(
					EmptyState(
						icon = R.drawable.ic_empty_favourites,
						textPrimary = R.string.no_bookmarks_yet,
						textSecondary = R.string.no_bookmarks_summary,
						actionStringRes = 0,
					),
				)
			} else {
				mapList(filteredBookmarks)
			}
		}
		.catch { e -> emit(listOf(e.toErrorState(canRetry = false))) }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	fun setSelectedGroupTab(tab: BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	fun setSelectedSourceTags(tags: Set<SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	fun removeBookmarks(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handle = repository.removeBookmarks(ids)
			onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
		}
	}

	fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			val tasks = content.value.mapNotNull {
				if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
				PageSaveHelper.Task(
					manga = it.manga,
					chapterId = it.chapterId,
					pageNumber = it.page + 1,
					page = it.toContentPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			val msg = if (dest.size == 1) R.string.page_saved else R.string.pages_saved
			onActionDone.call(ReversibleAction(msg, null))
		}
	}

	private fun mapList(data: Map<Content, List<Bookmark>>): List<ListModel> {
		val result = ArrayList<ListModel>(data.values.sumOf { it.size + 1 })
		for ((manga, bookmarks) in data) {
			result.add(ListHeader(manga.title, R.string.more, manga))
			result.addAll(bookmarks)
		}
		return result
	}

	private fun Map<Content, List<Bookmark>>.filterByTopBar(
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
	): Map<Content, List<Bookmark>> {
		if (groupTab == BrowseGroupTab.All && sourceTags.isEmpty()) {
			return this
		}
		val result = LinkedHashMap<Content, List<Bookmark>>(size)
		for ((content, bookmarks) in this) {
			val source = content.source
			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)
			val matchesGroup = groupTab.matchesContentGroup(contentGroup) && groupTab.matchesOriginGroup(originGroup)
			val matchesSourceTag = sourceTags.isEmpty() || sourceTags.any { it.matches(contentGroup, originGroup) }
			if (matchesGroup && matchesSourceTag) {
				result[content] = bookmarks
			}
		}
		return result
	}
}
