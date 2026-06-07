package org.skepsun.kototoro.home.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.BaseFragment
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.databinding.FragmentHomeBinding
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab

import org.skepsun.kototoro.main.ui.owners.BottomNavOwner
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.home.ui.compose.HomeScreenActions
import org.skepsun.kototoro.home.ui.compose.HomeScreen
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.skepsun.kototoro.main.ui.MainActivity

@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>(), SearchBarFilterViewController.Callback {
	private val mainViewModel: org.skepsun.kototoro.main.ui.MainViewModel by activityViewModels()


	@javax.inject.Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<HomeViewModel>()
	private var isHomeChromeHidden = false
	private var filterMenuProvider: SearchBarFilterViewController? = null

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeBinding {
		return FragmentHomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)

		
		filterMenuProvider = SearchBarFilterViewController(this)
		filterMenuProvider?.attachTo(this)
		showHomeChrome()

		viewModel.onOpenContent.observeEvent(viewLifecycleOwner) { event ->
			event.entityId?.let { entityId ->
				router.openEntityDetails(
					entityId = entityId,
					preferredLocalMangaId = event.preferredLocalMangaId ?: event.content.id,
				)
			} ?: router.openDetails(event.content, binding.root)
        }

        binding.composeView.setContent {
            val state by viewModel.summaryState.collectAsStateWithLifecycle()
            val isRandomLoading by viewModel.isRandomLoading.collectAsStateWithLifecycle()

            syncSelectedTab(state.selectedTab)
			
			KototoroTheme {
				val actions = remember(router, viewModel) {
					HomeScreenActions(
						onSettingsClick = { router.openSettings() },
						onReaderSettingsClick = { router.openReaderSettings() },
						onViewAllRecentClick = { router.openHistory(currentBrowseGroupTab()) },
						onViewAllUpdatesClick = { /* router.openMangaUpdates(currentBrowseGroupTab()) */ },
						onViewAllRecommendationsClick = { /* router.openSuggestions(currentBrowseGroupTab()) */ },
						onRecentSearchClick = { query -> router.openSearch(query) },
						onSourceSettingsClick = { router.openSourcesSettings() },
						onLibraryOpenClick = { router.openFavorites() },
						onBookmarksClick = { /* router.openBookmarks() */ },
						onLocalClick = { router.openList(org.skepsun.kototoro.core.model.LocalMangaSource, null, null) },
						onDownloadsClick = { router.openDownloads() },
						onRandomClick = { viewModel.openRandom() },
						onAutoTranslateClick = { router.openTranslationSettings() },
					)
				}
				HomeScreen(
					state = state,
					onContentClick = { content, coverBounds, _ ->
						viewModel.openContent(content)
					},
					actions = actions,
					isRandomLoading = isRandomLoading
				)
			}
		}
	}

	private fun syncSelectedTab(tab: HomeContentTab?) {
		filterMenuProvider?.updateIcons()
	}

    private fun currentBrowseGroupTab(): BrowseGroupTab {
        return when (viewModel.summaryState.value.selectedTab) {
            HomeContentTab.MANGA -> BrowseGroupTab.Content
            HomeContentTab.NOVEL -> BrowseGroupTab.Novel
            HomeContentTab.VIDEO -> BrowseGroupTab.Video
            null -> BrowseGroupTab.All
        }
	}

	override fun onDestroyView() {
		filterMenuProvider?.destroy()
		filterMenuProvider = null
		showHomeChrome()
		super.onDestroyView()
	}

	private fun hideHomeChrome() {
		if (isHomeChromeHidden) return
		val bottomNav = (activity as? BottomNavOwner)?.bottomNav
		if (bottomNav?.isPinned == true) return
				bottomNav?.hide()
		isHomeChromeHidden = true
	}

	private fun showHomeChrome() {
		if (!isHomeChromeHidden) return
				(activity as? BottomNavOwner)?.bottomNav?.show()
		isHomeChromeHidden = false
	}
	
	// === SearchBarFilterViewController.Callback implementation ===

	override fun onContentTypeSelected(tab: BrowseGroupTab) {
		val homeTab = when (tab) {
			BrowseGroupTab.Content -> HomeContentTab.MANGA
			BrowseGroupTab.Novel -> HomeContentTab.NOVEL
			BrowseGroupTab.Video -> HomeContentTab.VIDEO
			else -> null
		}
		viewModel.setSelectedTab(homeTab)
	}

	override fun onSourceTagSelected(tag: SourceTag?) {
		val current = getSelectedSourceTags()
		val selectedTags = when {
			tag == null -> emptySet()
			tag in current -> current - tag
			else -> current + tag
		}
		viewModel.setSelectedSourceTags(selectedTags)
	}

	override fun getSelectedContentType(): BrowseGroupTab {
		return currentBrowseGroupTab()
	}

    override fun getSelectedSourceTags(): Set<SourceTag> {
        return viewModel.summaryState.value.selectedSourceTags
    }

	override fun isLanguagePresetFilterVisible(): Boolean = settings.isShowLanguagePresetFilter
	override fun isContentTypeFilterVisible(): Boolean = settings.isShowContentTypeFilter && true

	override fun isSourceTagFilterVisible(): Boolean = settings.isShowSourceTagFilter && true

	override fun isContentTypeEnabled(tab: BrowseGroupTab): Boolean {
		val selectedTags = getSelectedSourceTags()
		return selectedTags.isEmpty() || selectedTags.any { it.supportsContentTab(tab) }
	}

	override fun isSourceTagEnabled(tag: SourceTag): Boolean {
		return getSelectedContentType().supportsSourceTag(tag)
	}

	override fun onApplyWindowInsets(view: android.view.View, insets: androidx.core.view.WindowInsetsCompat): androidx.core.view.WindowInsetsCompat {
		requireViewBinding().root.clipToPadding = false
		return insets
	}
}
