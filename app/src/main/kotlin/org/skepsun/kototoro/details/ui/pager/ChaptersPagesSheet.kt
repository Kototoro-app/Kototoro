package org.skepsun.kototoro.details.ui.pager

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.skepsun.kototoro.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.skepsun.kototoro.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.skepsun.kototoro.core.ui.sheet.AdaptiveSheetCallback
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.util.MenuInvalidator
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.util.ext.menuView
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.databinding.SheetChaptersPagesBinding
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.ReadButtonDelegate
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.bookmarks.compose.BookmarksScreenRoot
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionBar
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChaptersScreenRoot
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.details.ui.pager.pages.compose.PagesScreenRoot
import org.skepsun.kototoro.download.ui.worker.DownloadStartedObserver
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject

@AndroidEntryPoint
class ChaptersPagesSheet : BaseAdaptiveSheet<SheetChaptersPagesBinding>(),
    TabLayout.OnTabSelectedListener,
    AdaptiveSheetCallback {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var pageSaveHelperFactory: org.skepsun.kototoro.reader.ui.PageSaveHelper.Factory

    private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)
    private val pagesViewModel by viewModels<PagesViewModel>()
    private val bookmarksViewModel by viewModels<BookmarksViewModel>()

    private var isFoldUnfolded: Boolean = false
    private var activeTabs: List<Int> = emptyList()
    private var halfHeightPx: Int = BottomSheetBehavior.PEEK_HEIGHT_AUTO
    private var threeQuarterHeightPx: Int = BottomSheetBehavior.PEEK_HEIGHT_AUTO
    private var hasAppliedInitialState: Boolean = false
    private var lastKnownSheetTop: Int = -1
    private var pendingSnapBottomSheet: View? = null
    private val snapAfterSlideIdle = Runnable {
        val bottomSheet = pendingSnapBottomSheet ?: return@Runnable
        Log.d(LOG_TAG, "snapAfterSlideIdle: top=$lastKnownSheetTop")
        snapToNearestAnchor(bottomSheet)
    }

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetChaptersPagesBinding {
        return SheetChaptersPagesBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: SheetChaptersPagesBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        disableFitToContents()

        val args = arguments ?: Bundle.EMPTY
        val source = resolveContentSource()
        val contentType = source?.getContentType()
        val tabsList = resolveAvailableTabIds(contentType)
        activeTabs = tabsList

        var selectedTabId = args.getInt(AppRouter.KEY_TAB, settings.defaultDetailsTab)
        selectedTabId = resolveSelectedTabId(selectedTabId, tabsList)

        binding.tabs.removeAllTabs()
        for (tabId in tabsList) {
            binding.tabs.addTab(
                binding.tabs.newTab()
                    .setIcon(tabIconRes(tabId))
                    .setContentDescription(tabTitleRes(tabId)),
            )
        }

        (viewModel as? DetailsViewModel)?.let { dvm ->
            ReadButtonDelegate(binding.splitButtonRead, dvm, router).attach(viewLifecycleOwner)
        }

        val context = requireContext()
        val pageSaveHelper = pageSaveHelperFactory.create(this)
        val viewForSnackbar = binding.composePager

        binding.composePager.apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                val composeScope = rememberCoroutineScope()
                var chapterSelectionState by remember { mutableStateOf<ChapterSelectionUiState?>(null) }
                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                    initialPage = tabsList.indexOf(selectedTabId).coerceAtLeast(0),
                    pageCount = { tabsList.size },
                )

                LaunchedEffect(pagerState.currentPage) {
                    if (binding.tabs.selectedTabPosition != pagerState.currentPage) {
                        binding.tabs.selectTab(binding.tabs.getTabAt(pagerState.currentPage))
                    }
                    this@ChaptersPagesSheet.onPageChanged(tabsList[pagerState.currentPage])
                }

                DisposableEffect(binding.tabs, pagerState) {
                    val listener = object : TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: TabLayout.Tab) {
                            if (pagerState.currentPage == tab.position) {
                                return
                            }
                            composeScope.launch {
                                pagerState.animateScrollToPage(tab.position)
                            }
                        }

                        override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

                        override fun onTabReselected(tab: TabLayout.Tab?) = Unit
                    }
                    binding.tabs.addOnTabSelectedListener(listener)
                    onDispose {
                        binding.tabs.removeOnTabSelectedListener(listener)
                    }
                }

                org.skepsun.kototoro.core.ui.theme.KototoroTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(rememberNestedScrollInteropConnection()),
                    ) {
                        chapterSelectionState?.let { selState ->
                            ChapterSelectionBar(
                                state = selState,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (tabsList[page]) {
                                TAB_CHAPTERS -> ChaptersScreenRoot(
                                    viewModel = viewModel,
                                    router = router,
                                    context = context,
                                    viewForSnackbar = viewForSnackbar,
                                    lifecycleOwner = viewLifecycleOwner,
                                    handleSelectionBackPressInternally = true,
                                    onSelectionStateChange = { state ->
                                        chapterSelectionState = state
                                        viewBinding?.toolbar?.isVisible = state == null
                                    },
                                )

                                TAB_PAGES -> PagesScreenRoot(
                                    activityViewModel = viewModel,
                                    router = router,
                                    context = context,
                                    pageSaveHelper = pageSaveHelper,
                                    viewForSnackbar = viewForSnackbar,
                                    lifecycleOwner = viewLifecycleOwner,
                                    viewModel = pagesViewModel,
                                )

                                TAB_BOOKMARKS -> BookmarksScreenRoot(
                                    activityViewModel = viewModel,
                                    router = router,
                                    context = context,
                                    viewModel = bookmarksViewModel,
                                )
                            }
                        }
                    }
                }
            }
        }
        binding.tabs.isVisible = tabsList.size > 1

        installThreeStateSheetBehavior(binding)

        val menuProvider = ChapterPagesMenuProvider(
            viewModel = viewModel,
            sheet = this,
            getCurrentTab = {
                tabsList.getOrElse(binding.tabs.selectedTabPosition.coerceAtLeast(0)) { tabsList.firstOrNull() ?: TAB_CHAPTERS }
            },
            getTabCount = { binding.tabs.tabCount },
            settings = settings,
        )
        onBackPressedDispatcher.addCallback(viewLifecycleOwner, menuProvider)
        binding.toolbar.addMenuProvider(menuProvider)

        val menuInvalidator = MenuInvalidator(binding.toolbar)
        viewModel.isChaptersReversed.observe(viewLifecycleOwner, menuInvalidator)
        viewModel.isChaptersInGridView.observe(viewLifecycleOwner, menuInvalidator)
        viewModel.isHideReadChapters.observe(viewLifecycleOwner, menuInvalidator)
        viewModel.isDownloadedOnly.observe(viewLifecycleOwner, menuInvalidator)

        addSheetCallback(this, viewLifecycleOwner)

        viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
        if (dialog != null) {
            viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.composePager, this))
            viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.composePager))
            viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.composePager))
        } else {
            PeekHeightController(arrayOf(binding.headerBar, binding.toolbar)).attach()
        }

        observeFoldableStateForReadButton()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun onStateChanged(bottomSheet: View, newState: Int) {
        val binding = viewBinding ?: return
        lastKnownSheetTop = bottomSheet.top
        binding.layoutTouchBlock.isTouchEventsAllowed = dialog != null || newState != BottomSheetBehavior.STATE_HIDDEN
        Log.d(
            LOG_TAG,
            "onStateChanged: state=${stateName(newState)}, top=${bottomSheet.top}, height=${bottomSheet.height}, halfHeightPx=$halfHeightPx, threeQuarterHeightPx=$threeQuarterHeightPx",
        )
        if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
            return
        }
        binding.toolbar.menuView?.isVisible = newState == STATE_EXPANDED
        binding.splitButtonRead.isVisible = newState != STATE_EXPANDED && viewModel is DetailsViewModel
    }

    override fun onSlide(bottomSheet: View, slideOffset: Float) {
        lastKnownSheetTop = bottomSheet.top
        pendingSnapBottomSheet = bottomSheet
        viewBinding?.root?.removeCallbacks(snapAfterSlideIdle)
        viewBinding?.root?.postDelayed(snapAfterSlideIdle, SNAP_IDLE_DELAY_MS)
        Log.d(LOG_TAG, "onSlide: offset=$slideOffset, top=${bottomSheet.top}, height=${bottomSheet.height}")
    }

    override fun onTabSelected(tab: TabLayout.Tab?) = Unit

    override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

    override fun onTabReselected(tab: TabLayout.Tab?) = Unit

    override fun expandAndLock() {
        super.expandAndLock()
        adjustLockState()
    }

    override fun unlock() {
        super.unlock()
        adjustLockState()
    }

    private fun installThreeStateSheetBehavior(binding: SheetChaptersPagesBinding) {
        val bottomDialog = dialog as? BottomSheetDialog ?: return
        val behavior = bottomDialog.behavior
        val bottomSheet = bottomDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        pendingSnapBottomSheet = bottomSheet
        binding.root.doOnLayout {
            val windowHeight = (binding.root.rootView.height).takeIf { it > 0 } ?: binding.root.height
            if (windowHeight <= 0) {
                return@doOnLayout
            }
            val halfHeight = (windowHeight * HALF_HEIGHT_RATIO)
                .toInt()
                .coerceAtLeast(MIN_HALF_HEIGHT_PX)
                .coerceAtMost(windowHeight)
            val threeQuarterHeight = (windowHeight * THREE_QUARTER_HEIGHT_RATIO)
                .toInt()
                .coerceAtLeast(halfHeight)
                .coerceAtMost(windowHeight)
            halfHeightPx = halfHeight
            threeQuarterHeightPx = threeQuarterHeight
            behavior.isFitToContents = false
            behavior.skipCollapsed = false
            behavior.isHideable = true
            behavior.halfExpandedRatio = ((windowHeight - halfHeight).toFloat() / windowHeight)
                .coerceIn(0.1f, 0.9f)
            behavior.peekHeight = threeQuarterHeight
            behavior.expandedOffset = 0
            Log.d(
                LOG_TAG,
                "installThreeStateSheetBehavior: windowHeight=$windowHeight, halfHeight=$halfHeight, threeQuarterHeight=$threeQuarterHeight, peekHeight=${behavior.peekHeight}, halfExpandedRatio=${behavior.halfExpandedRatio}, skipCollapsed=${behavior.skipCollapsed}, isHideable=${behavior.isHideable}, state=${stateName(behavior.state)}"
            )
            if (!hasAppliedInitialState) {
                hasAppliedInitialState = true
                binding.root.post {
                    if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                        Log.d(LOG_TAG, "installThreeStateSheetBehavior: set initial state to THREE_QUARTERS")
                        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                }
            }
        }
    }

    private fun snapToNearestAnchor(bottomSheet: View) {
        val behavior = (dialog as? BottomSheetDialog)?.behavior ?: return
        if (halfHeightPx <= 0 || threeQuarterHeightPx <= 0) {
            return
        }
        val parentHeight = (bottomSheet.parent as? View)?.height ?: return
        val fullTop = 0
        val threeQuarterTop = (parentHeight - threeQuarterHeightPx).coerceAtLeast(0)
        val halfTop = (parentHeight - halfHeightPx).coerceAtLeast(0)
        val hiddenTop = parentHeight
        val currentTop = lastKnownSheetTop.takeIf { it >= 0 } ?: bottomSheet.top
        val targets = listOf(
            BottomSheetBehavior.STATE_EXPANDED to fullTop,
            BottomSheetBehavior.STATE_COLLAPSED to threeQuarterTop,
            BottomSheetBehavior.STATE_HALF_EXPANDED to halfTop,
            BottomSheetBehavior.STATE_HIDDEN to hiddenTop,
        )
        val targetState = targets.minByOrNull { (_, top) ->
            kotlin.math.abs(currentTop - top)
        }?.first ?: return
        val targetTop = targets.first { it.first == targetState }.second
        val currentState = behavior.state
        if (
            currentState == BottomSheetBehavior.STATE_EXPANDED ||
            currentState == BottomSheetBehavior.STATE_COLLAPSED ||
            currentState == BottomSheetBehavior.STATE_HALF_EXPANDED ||
            currentState == BottomSheetBehavior.STATE_HIDDEN
        ) {
            val currentStateTop = when (currentState) {
                BottomSheetBehavior.STATE_EXPANDED -> fullTop
                BottomSheetBehavior.STATE_COLLAPSED -> threeQuarterTop
                BottomSheetBehavior.STATE_HALF_EXPANDED -> halfTop
                BottomSheetBehavior.STATE_HIDDEN -> hiddenTop
                else -> currentTop
            }
            if (kotlin.math.abs(currentTop - currentStateTop) <= SNAP_POSITION_TOLERANCE_PX) {
                viewBinding?.root?.removeCallbacks(snapAfterSlideIdle)
                return
            }
        }
        Log.d(
            LOG_TAG,
            "snapToNearestAnchor: currentTop=$currentTop, fullTop=$fullTop, threeQuarterTop=$threeQuarterTop, halfTop=$halfTop, hiddenTop=$hiddenTop, currentState=${stateName(currentState)}, target=${stateName(targetState)}, targetTop=$targetTop"
        )
        viewBinding?.root?.removeCallbacks(snapAfterSlideIdle)
        if (behavior.state != targetState) {
            behavior.state = targetState
        }
    }

    private fun adjustLockState() {
        viewBinding?.run {
            tabs.visibility = when {
                tabs.tabCount <= 1 -> View.GONE
                isLocked -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
    }

    fun selectTab(tab: Int) {
        val targetIndex = activeTabs.indexOf(tab)
        if (targetIndex < 0) {
            return
        }
        viewBinding?.tabs?.selectTab(viewBinding?.tabs?.getTabAt(targetIndex))
    }

    private fun onPageChanged(tabId: Int) {
        viewBinding?.toolbar?.invalidateMenu()
        settings.lastDetailsTab = tabId
    }

    private fun onNewChaptersChanged(counter: Int) {
        val tab = viewBinding?.tabs?.getTabAt(0) ?: return
        if (counter == 0) {
            tab.removeBadge()
        } else {
            val badge = tab.orCreateBadge
            badge.number = counter
        }
    }

    private fun observeFoldableStateForReadButton() {
        val owner = viewLifecycleOwner
        val foldableState = FoldableUtils.observeFoldableState(requireActivity(), owner)
        owner.lifecycleScope.launch {
            foldableState.collect { unfolded ->
                isFoldUnfolded = unfolded
                adjustReadSplitButtonScale()
            }
        }
    }

    private fun adjustReadSplitButtonScale() {
        val binding = viewBinding ?: return
        binding.splitButtonRead.scaleX = 0.8f
        binding.splitButtonRead.scaleY = 0.8f
    }

    private fun resolveContentSource(): ContentSource? {
        return when (viewModel) {
            is DetailsViewModel -> (viewModel as DetailsViewModel).manga.value?.source
            is org.skepsun.kototoro.video.ui.VideoChaptersViewModel ->
                (viewModel as org.skepsun.kototoro.video.ui.VideoChaptersViewModel).mangaDetails.value?.toContent()?.source

            else -> null
        }
    }

    private fun resolveAvailableTabIds(contentType: ContentType?): List<Int> = buildList {
        add(TAB_CHAPTERS)
        val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
        val isVideo = contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO
        if (settings.isPagesTabEnabled && !isNovel && !isVideo) {
            add(TAB_PAGES)
        }
        if (!isVideo) {
            add(TAB_BOOKMARKS)
        }
    }

    private fun resolveSelectedTabId(requestedTabId: Int, availableTabs: List<Int>): Int {
        return if (requestedTabId in availableTabs) {
            requestedTabId
        } else {
            when {
                requestedTabId > TAB_CHAPTERS -> {
                    availableTabs.getOrElse((requestedTabId - 1).coerceAtLeast(0)) { availableTabs.first() }
                }
                else -> availableTabs.first()
            }
        }
    }

    private fun tabTitleRes(tabId: Int): Int = when (tabId) {
        TAB_CHAPTERS -> R.string.chapters
        TAB_PAGES -> R.string.pages
        TAB_BOOKMARKS -> R.string.bookmarks
        else -> R.string.chapters
    }

    private fun tabIconRes(tabId: Int): Int = when (tabId) {
        TAB_PAGES -> R.drawable.ic_grid
        TAB_BOOKMARKS -> R.drawable.ic_bookmark
        else -> R.drawable.ic_list
    }

    private fun stateName(state: Int): String = when (state) {
        BottomSheetBehavior.STATE_COLLAPSED -> "THREE_QUARTERS"
        BottomSheetBehavior.STATE_HALF_EXPANDED -> "HALF"
        BottomSheetBehavior.STATE_EXPANDED -> "FULL"
        BottomSheetBehavior.STATE_DRAGGING -> "DRAGGING"
        BottomSheetBehavior.STATE_SETTLING -> "SETTLING"
        BottomSheetBehavior.STATE_HIDDEN -> "HIDDEN"
        else -> state.toString()
    }

    companion object {
        private const val LOG_TAG = "ChaptersPagesSheet"
        private const val HALF_HEIGHT_RATIO = 0.5f
        private const val THREE_QUARTER_HEIGHT_RATIO = 0.75f
        private const val MIN_HALF_HEIGHT_PX = 360
        private const val SNAP_IDLE_DELAY_MS = 90L
        private const val SNAP_POSITION_TOLERANCE_PX = 12

        const val TAB_CHAPTERS = 0
        const val TAB_PAGES = 1
        const val TAB_BOOKMARKS = 2
    }
}
