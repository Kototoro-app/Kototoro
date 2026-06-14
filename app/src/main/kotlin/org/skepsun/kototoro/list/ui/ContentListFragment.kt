package org.skepsun.kototoro.list.ui

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.appcompat.view.ActionMode
import androidx.collection.ArraySet
import androidx.fragment.app.activityViewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.ui.AutoFixService
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.BaseFragment
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.list.ListSelectionController
import org.skepsun.kototoro.core.ui.list.fastscroll.FastScroller
import org.skepsun.kototoro.core.ui.util.RecyclerViewOwner
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.ui.widgets.TipView
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.ext.addSupportMenuProvider
import org.skepsun.kototoro.core.util.ext.consumeAll
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.databinding.FragmentContentListBinding
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.adapter.ContentListListener
import org.skepsun.kototoro.list.ui.compose.KototoroContentListScreen
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.search.ui.ContentListActivity
import javax.inject.Inject

@AndroidEntryPoint
abstract class ContentListFragment :
	BaseFragment<FragmentContentListBinding>(),
	ContentListListener, // Kept to avoid breaking subclasses immediately
	RecyclerViewOwner,
	AppBarLayout.OnOffsetChangedListener,
	SearchBarFilterViewController.Callback {
	private val mainViewModel: org.skepsun.kototoro.main.ui.MainViewModel by activityViewModels()


	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	// Kept for signature compatibility
	open val isSwipeRefreshEnabled = true
	protected open val showSelectionRemoveOption = false

	private var filterMenuProvider: SearchBarFilterViewController? = null

	private val categoryChipIds = mutableMapOf<Long, Int>()
	private val contentTypeChipIds = mutableMapOf<BrowseGroupTab, Int>()
	private val sourceTagChipIds = mutableMapOf<SourceTag, Int>()

	protected abstract val viewModel: ContentListViewModel

	// Our new Compose layer state
	protected var composeSelectionIds by mutableStateOf<Set<Long>>(emptySet())

	protected val selectedItemsIds: Set<Long>
		get() = composeSelectionIds

	protected val selectedItems: Set<Content>
		get() {
			val items = viewModel.content.value
			val result = ArraySet<Content>(composeSelectionIds.size)
			for (item in items) {
				if (item is ContentListModel && item.id in composeSelectionIds) {
					result.add(item.manga)
				}
			}
			return result
		}

	override val recyclerView: RecyclerView?
		get() = null // We don't have a RecyclerView anymore!

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentContentListBinding.inflate(inflater, container, false)

	protected open fun sourceTagChipEntries(): List<SourceTag> = SourceTag.quickFilterEntries

	override fun onViewBindingCreated(binding: FragmentContentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)

		binding.composeView.apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				val items by viewModel.content.collectAsStateWithLifecycle(initialValue = emptyList())
				val listMode by viewModel.listMode.collectAsStateWithLifecycle(initialValue = ListMode.GRID)
				val gridScale by viewModel.gridScale.collectAsStateWithLifecycle(initialValue = 1f)
				val isRefreshing by viewModel.isLoading.collectAsStateWithLifecycle(initialValue = false)
				val topInset by mainViewModel.topContentInsetPx.collectAsStateWithLifecycle()
				val bottomInset by mainViewModel.bottomContentInsetPx.collectAsStateWithLifecycle()
				
				val density = androidx.compose.ui.platform.LocalDensity.current
				val contentPadding = androidx.compose.runtime.remember(topInset, bottomInset, density) {
					with(density) {
						androidx.compose.foundation.layout.PaddingValues(
							top = topInset.toDp(),
							bottom = bottomInset.toDp()
						)
					}
				}
				
				KototoroContentListScreen(
					contentPadding = contentPadding,
					items = items,
					listMode = listMode,
					isRefreshing = isRefreshing,
					showRemoveOption = showSelectionRemoveOption,
					onRefresh = { viewModel.onRefresh() },
					onLoadMore = { onScrolledToEnd() },
					gridScale = gridScale,
					selectedItemsIds = composeSelectionIds,
					onPrepareItemTransition = { _, _ -> },
					onItemClick = { item ->
						if (composeSelectionIds.isNotEmpty()) {
							composeSelectionIds = if (item.id in composeSelectionIds) composeSelectionIds - item.id else composeSelectionIds + item.id
						} else {
							val manga = item.toContentWithOverride()
							router.openResolvedDetails(manga, binding.root)
						}
					},
					onItemLongClick = { item ->
						if (composeSelectionIds.isEmpty()) {
							composeSelectionIds = setOf(item.id)
						} else {
							composeSelectionIds = if (item.id in composeSelectionIds) composeSelectionIds - item.id else composeSelectionIds + item.id
						}
					},
					onClearSelection = { composeSelectionIds = emptySet() },
					onSelectionAction = { action ->
						if (!onSelectionAction(action, composeSelectionIds)) {
							handleSelectionAction(action, composeSelectionIds)
						}
						composeSelectionIds = emptySet()
					},
					onQuickFilterOptionClick = { option ->
						(viewModel as? QuickFilterListener)?.toggleFilterOption(option)
					},
					onEmptyActionClick = ::onEmptyActionClick,
					onRetry = viewModel::onRetry,
				)
			}
		}
		addSupportMenuProvider(ContentListMenuProvider(this))

		viewModel.onError.observeEvent(
			viewLifecycleOwner, 
			SnackbarErrorObserver(
				host = binding.root, 
				fragment = this,
				resolver = exceptionResolver,
				onResolved = { resolved -> if (resolved) viewModel.onRetry() }
			)
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.root))

		val isInsideContainer = parentFragment != null
		val filterAnchorView: android.view.View? = null

		if (filterAnchorView != null) {
			filterMenuProvider = SearchBarFilterViewController(this)
			filterMenuProvider?.attachTo(this)
			binding.filterScrollView.visibility = View.GONE
		} else if (!isInsideContainer) {
			if (isContentTypeFilterVisible()) {
				rebuildContentTypeChips(binding)
			} else {
				binding.chipGroupContentType.visibility = View.GONE
			}
			if (isSourceTagFilterVisible()) {
				rebuildSourceTagChips(binding)
			} else {
				binding.chipGroupSourceTag.visibility = View.GONE
			}
			updateFilterScrollViewVisibility(binding)
		} else {
			binding.filterScrollView.visibility = View.GONE
		}

		viewModel.currentGroupTab.observe(viewLifecycleOwner) { tab ->
			filterMenuProvider?.updateIcons()
			if (filterAnchorView == null && !isInsideContainer) {
				updateContentTypeChipsSelection(binding, tab)
				updateSourceTagChipsEnabled(binding, tab)
			}
		}
		viewModel.currentSourceTags.observe(viewLifecycleOwner) { tags ->
			filterMenuProvider?.updateIcons()
			if (filterAnchorView == null && !isInsideContainer) {
				updateSourceTagChipsSelection(binding, tags)
				updateContentTypeChipsEnabled(binding, tags)
			}
		}
		viewModel.availableCategories.observe(viewLifecycleOwner) { categories ->
			rebuildCategoryChips(binding, categories)
		}
		viewModel.currentCategoryIds.observe(viewLifecycleOwner) { ids ->
			updateCategoryChipsSelection(binding, ids)
		}
	}

	protected open fun onSelectionAction(action: SelectionAction, ids: Set<Long>): Boolean {
		return false
	}

	private fun handleSelectionAction(action: SelectionAction, ids: Set<Long>) {
		when (action) {
			SelectionAction.SELECT_ALL -> {
				val allIds = viewModel.content.value.mapNotNull { (it as? ContentListModel)?.id }.toSet()
				composeSelectionIds = allIds
			}
			SelectionAction.SHARE -> {
				ShareHelper(requireContext()).shareContentLinks(selectedItems)
			}
			SelectionAction.FAVOURITE -> {
				router.showFavoriteDialog(selectedItems)
			}
			SelectionAction.SAVE -> {
				router.showDownloadDialog(selectedItems, viewBinding?.root)
			}
			SelectionAction.EDIT_OVERRIDE -> {
				router.openContentOverrideConfig(selectedItems.singleOrNull() ?: return)
			}
			SelectionAction.FIX -> {
				buildAlertDialog(context ?: return, isCentered = true) {
					setTitle(R.string.fix)
					setMessage(R.string.manga_fix_prompt)
					setNegativeButton(android.R.string.cancel, null)
					setPositiveButton(R.string.fix) { _, _ ->
						AutoFixService.start(context, ids)
					}
				}.show()
			}
			SelectionAction.REMOVE -> Unit
			SelectionAction.PIN -> Unit
			SelectionAction.MARK_AS_COMPLETED -> Unit
		}
	}

	override fun onOffsetChanged(appBarLayout: AppBarLayout?, verticalOffset: Int) {
		val binding = viewBinding ?: return
		val appBar = appBarLayout ?: return
		val insets = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.statusBars())
		val statusBarHeight = insets?.top ?: 0
		val topPadding = Math.max(0, statusBarHeight - appBar.bottom)
		if (binding.filterScrollView.paddingTop != topPadding) {
			binding.filterScrollView.updatePadding(top = topPadding)
		}
	}

	override fun onDestroyView() {
		filterMenuProvider?.destroy()
		filterMenuProvider = null
		categoryChipIds.clear()
		contentTypeChipIds.clear()
		sourceTagChipIds.clear()
		super.onDestroyView()
	}

	// Legacy callbacks mappings to avoid breaking subclasses immediately
	open fun onScrolledToEnd() = Unit
	override fun onItemClick(item: ContentListModel, view: View) = Unit
	override fun onItemLongClick(item: ContentListModel, view: View): Boolean = false
	override fun onItemContextClick(item: ContentListModel, view: View): Boolean = false
	override fun onReadClick(manga: Content, view: View) = Unit
	override fun onTagClick(manga: Content, tag: ContentTag, view: View) = Unit
	override fun onFilterClick(view: View?) = Unit
	override fun onListHeaderClick(item: ListHeader, view: View) = Unit
	@CallSuper
	open fun onRefresh() = Unit
	open fun onLoadingStateChanged(isLoading: Boolean) = Unit
	override fun onFilterOptionClick(option: ListFilterOption) = Unit
	override fun onEmptyActionClick() = Unit
	override fun onPrimaryButtonClick(tipView: TipView) = Unit
	override fun onSecondaryButtonClick(tipView: TipView) = Unit
	override fun onRetryClick(error: Throwable) = Unit

	// --- Custom filter chip builders ---
	// === SearchBarFilterViewController.Callback implementation ===

	override fun onContentTypeSelected(tab: BrowseGroupTab) {
		viewModel.setSelectedGroupTab(tab)
	}

	override fun onSourceTagSelected(tag: SourceTag?) {
		val selectedTags = if (tag != null) setOf(tag) else emptySet()
		viewModel.setSelectedSourceTags(selectedTags)
	}

	override fun getSelectedContentType(): BrowseGroupTab = viewModel.currentGroupTab.value
	override fun getSelectedSourceTags(): Set<SourceTag> = viewModel.currentSourceTags.value
	override fun getSourceTagEntries(): List<SourceTag> = sourceTagChipEntries()
	override fun isLanguagePresetFilterVisible(): Boolean = false
	override fun isContentTypeFilterVisible(): Boolean = settings.isShowContentTypeFilter
	override fun isSourceTagFilterVisible(): Boolean = settings.isShowSourceTagFilter

	override fun isContentTypeEnabled(tab: BrowseGroupTab): Boolean {
		val selectedTags = viewModel.currentSourceTags.value
		return selectedTags.isEmpty() || selectedTags.any { it.supportsContentTab(tab) }
	}

	override fun isSourceTagEnabled(tag: SourceTag): Boolean {
		return viewModel.currentGroupTab.value.supportsSourceTag(tag)
	}

	private fun rebuildCategoryChips(binding: FragmentContentListBinding, categories: List<FavouriteCategory>) {
		val chipGroup = binding.chipGroupCategory
		chipGroup.removeAllViews()
		categoryChipIds.clear()

		if (categories.isEmpty()) {
			chipGroup.visibility = View.GONE
			updateFilterScrollViewVisibility(binding)
			return
		}

		chipGroup.visibility = View.VISIBLE
		updateFilterScrollViewVisibility(binding)

		val colors = createChipColors()
		val density = resources.displayMetrics.density
		val currentIds = viewModel.currentCategoryIds.value

		categories.forEach { category ->
			val chip = createCompactChip(
				text = category.title,
				iconRes = R.drawable.ic_filter_menu,
				colors = colors,
				density = density,
			)
			chip.text = category.title // Categories show text
			chip.chipStartPadding = 12 * density
			chip.chipEndPadding = 12 * density
			
			chip.tag = category.id
			chip.isChecked = category.id in currentIds
			chip.setOnCheckedChangeListener { _, isChecked ->
				val selectedIds = viewModel.currentCategoryIds.value.toMutableSet()
				if (isChecked) {
					selectedIds.add(category.id)
				} else {
					selectedIds.remove(category.id)
				}
				viewModel.setSelectedCategoryIds(selectedIds)
			}
			categoryChipIds[category.id] = chip.id
			chipGroup.addView(chip)
		}
	}

	private fun updateCategoryChipsSelection(binding: FragmentContentListBinding, ids: Set<Long>) {
		categoryChipIds.forEach { (categoryId, id) ->
			binding.chipGroupCategory.findViewById<Chip>(id)?.isChecked = (categoryId in ids)
		}
	}

	private fun updateFilterScrollViewVisibility(binding: FragmentContentListBinding) {
		val hasContent = binding.chipGroupContentType.visibility == View.VISIBLE ||
				binding.chipGroupSourceTag.visibility == View.VISIBLE ||
				binding.chipGroupCategory.visibility == View.VISIBLE
		
		if (filterMenuProvider != null) {
			binding.filterScrollView.visibility = if (binding.chipGroupCategory.visibility == View.VISIBLE) View.VISIBLE else View.GONE
		} else if (parentFragment == null) {
			binding.filterScrollView.visibility = if (hasContent) View.VISIBLE else View.GONE
		}
	}

	private fun rebuildContentTypeChips(binding: FragmentContentListBinding) {
		val group = binding.chipGroupContentType
		group.removeAllViews()
		contentTypeChipIds.clear()
		val colors = createChipColors()
		val density = resources.displayMetrics.density
		val tabs = listOf(BrowseGroupTab.Content, BrowseGroupTab.Novel, BrowseGroupTab.Video)
		for (tab in tabs) {
			val chip = createCompactChip(getString(tab.titleRes), tab.iconRes, colors, density)
			chip.isChecked = (viewModel.currentGroupTab.value == tab)
			chip.setOnClickListener {
				val current = viewModel.currentGroupTab.value
				viewModel.setSelectedGroupTab(if (current == tab) BrowseGroupTab.All else tab)
			}
			contentTypeChipIds[tab] = chip.id
			group.addView(chip)
		}
		group.visibility = View.VISIBLE
	}

	private fun rebuildSourceTagChips(binding: FragmentContentListBinding) {
		val group = binding.chipGroupSourceTag
		group.removeAllViews()
		sourceTagChipIds.clear()
		val colors = createChipColors()
		val density = resources.displayMetrics.density
		val tags = sourceTagChipEntries()
		for (tag in tags) {
			val chip = createCompactChip(getString(tag.titleRes), null, colors, density)
			chip.isChecked = viewModel.currentSourceTags.value.contains(tag)
			chip.setOnClickListener {
				val selected = viewModel.currentSourceTags.value
				val newTags = if (tag in selected) selected - tag else selected + tag
				viewModel.setSelectedSourceTags(newTags)
			}
			sourceTagChipIds[tag] = chip.id
			group.addView(chip)
		}
		group.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
	}

	private fun updateContentTypeChipsSelection(binding: FragmentContentListBinding, selectedTab: BrowseGroupTab) {
		contentTypeChipIds.forEach { (tab, viewId) ->
			binding.chipGroupContentType.findViewById<Chip>(viewId)?.isChecked = (tab == selectedTab)
		}
	}

	private fun updateSourceTagChipsSelection(binding: FragmentContentListBinding, selectedTags: Set<SourceTag>) {
		sourceTagChipIds.forEach { (tag, viewId) ->
			binding.chipGroupSourceTag.findViewById<Chip>(viewId)?.isChecked = (tag in selectedTags)
		}
	}

	private fun updateContentTypeChipsEnabled(binding: FragmentContentListBinding, selectedTags: Set<SourceTag>) {
		contentTypeChipIds.forEach { (tab, viewId) ->
			val chip = binding.chipGroupContentType.findViewById<Chip>(viewId) ?: return@forEach
			chip.isEnabled = selectedTags.isEmpty() || selectedTags.any { it.supportsContentTab(tab) }
			chip.alpha = if (chip.isEnabled) 1.0f else 0.5f
		}
	}

	private fun updateSourceTagChipsEnabled(binding: FragmentContentListBinding, selectedTab: BrowseGroupTab) {
		sourceTagChipIds.forEach { (tag, viewId) ->
			val chip = binding.chipGroupSourceTag.findViewById<Chip>(viewId) ?: return@forEach
			chip.isEnabled = selectedTab.supportsSourceTag(tag)
			chip.alpha = if (chip.isEnabled) 1.0f else 0.5f
		}
	}

	private data class ChipColors(
		val bg: ColorStateList,
		val text: ColorStateList,
		val stroke: ColorStateList,
	)

	private fun createChipColors(): ChipColors {
		val bgUnchecked = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurfaceVariant, 0)
		val bgChecked = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorPrimaryContainer, 0)
		val bgDisabled = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, 0)
		
		val textUnchecked = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
		val textChecked = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnPrimaryContainer, 0)
		val textDisabled = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, 0).let {
			ColorStateList.valueOf(it).withAlpha(97).defaultColor // ~38% alpha
		}

		val stateDisabled = intArrayOf(-android.R.attr.state_enabled)
		val stateChecked = intArrayOf(android.R.attr.state_checked)
		val stateDefault = intArrayOf()

		return ChipColors(
			bg = ColorStateList(
				arrayOf(stateDisabled, stateChecked, stateDefault),
				intArrayOf(bgDisabled, bgChecked, bgUnchecked)
			),
			text = ColorStateList(
				arrayOf(stateDisabled, stateChecked, stateDefault),
				intArrayOf(textDisabled, textChecked, textUnchecked)
			),
			stroke = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT),
		)
	}

	private fun createCompactChip(
		text: String,
		iconRes: Int?,
		colors: ChipColors,
		density: Float,
	): Chip {
		return Chip(requireContext()).apply {
			id = View.generateViewId()
			this.text = text
			contentDescription = text
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				tooltipText = text
			}

			isCheckable = true
			isChipIconVisible = iconRes != null
			if (iconRes != null) {
				setChipIconResource(iconRes)
				chipIconSize = 20f * density
				chipIconTint = colors.text
			}

			chipMinHeight = 32 * density
			minHeight = 0
			chipStartPadding = 12 * density
			chipEndPadding = 12 * density
			textStartPadding = if (iconRes != null) 6 * density else 0f
			textEndPadding = 0f
			setEnsureMinTouchTargetSize(false)

			chipStrokeWidth = 0f
			chipBackgroundColor = colors.bg
			setTextColor(colors.text)
		}
	}

	override fun onApplyWindowInsets(view: android.view.View, insets: androidx.core.view.WindowInsetsCompat): androidx.core.view.WindowInsetsCompat {
		requireViewBinding().root.clipToPadding = false
		return insets
	}
}
