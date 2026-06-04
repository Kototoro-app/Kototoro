package org.skepsun.kototoro.favourites.ui.compose

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.glass.LocalHazeState
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.container.FavouriteTabModel
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.favourites.ui.migration.compose.SourceMigrationPanel
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarTabItem
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.parsers.model.Content
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroFavoritesHostRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    initialCategoryId: Long = NO_ID,
    initialCategoryTitle: String? = null,
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    registerFilterCallback: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    viewModel: FavouritesContainerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showMigrationPanel by viewModel.showMigrationPanel.collectAsStateWithLifecycle()
    var lastLoadedUiState by remember {
        mutableStateOf<FavouritesContainerViewModel.FavoritesHostUiState?>(null)
    }
    LaunchedEffect(uiState) {
        if (!uiState.isLoading) {
            lastLoadedUiState = uiState
        }
    }
    val displayedUiState = lastLoadedUiState?.takeIf { uiState.isLoading } ?: uiState

    val mainActivity = LocalContext.current as? MainActivity
    val globalState = viewModel.globalFavoritesState
    val selectedGroupTab by globalState.selectedGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by globalState.selectedSourceTags.collectAsStateWithLifecycle()

    DisposableEffect(mainActivity, globalState, selectedGroupTab, selectedSourceTags, registerFilterCallback) {
        if (!registerFilterCallback) { onDispose { } }
        else {
            val callback = object : SearchBarFilterViewController.Callback {
                override fun isSourceTagFilterVisible() = true
                override fun getSourceTagEntries() = SourceTag.quickFilterEntries
                override fun getSelectedContentType() = selectedGroupTab
                override fun onContentTypeSelected(tab: BrowseGroupTab) {
                    globalState.setSelectedGroupTab(if (selectedGroupTab == tab) BrowseGroupTab.All else tab)
                }
                override fun getSelectedSourceTags() = selectedSourceTags
                override fun onSourceTagSelected(tag: SourceTag?) {
                    when {
                        tag == null -> globalState.clearSourceTags()
                        tag in selectedSourceTags -> globalState.setSelectedSourceTags(selectedSourceTags - tag)
                        else -> globalState.setSelectedSourceTags(selectedSourceTags + tag)
                    }
                }
            }
            mainActivity?.setActiveFilterCallback(callback)
            onDispose { mainActivity?.clearActiveFilterCallback(callback) }
        }
    }

    SideEffect {
        if (!registerFilterCallback) return@SideEffect
        mainActivity?.refreshFilters()
    }

    val displayCategories = remember(displayedUiState.categories, initialCategoryId, initialCategoryTitle) {
        val categories = displayedUiState.categories
        if (initialCategoryId == NO_ID || categories.any { it.id == initialCategoryId }) {
            categories
        } else {
            categories + FavouriteTabModel(id = initialCategoryId, title = initialCategoryTitle)
        }
    }
    var selectedCategoryId by rememberSaveable(initialCategoryId) { mutableLongStateOf(initialCategoryId) }
    var initialSelectionApplied by rememberSaveable(initialCategoryId) { mutableStateOf(false) }
    var childTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var childTopBarOverrideGeneration by remember { mutableIntStateOf(-1) }
    var activeChildOverrideGeneration by remember { mutableIntStateOf(0) }
    var lastActiveCategoryId by remember { mutableStateOf<Long?>(null) }
    val allFavouritesLabel = stringResource(R.string.all_favourites)
    val activeCategoryId = selectedCategoryId.takeIf { selectedId ->
        displayCategories.any { it.id == selectedId }
    } ?: displayCategories.firstOrNull()?.id ?: NO_ID
    val pagerState = rememberPagerState(pageCount = { displayCategories.size.coerceAtLeast(1) })
    val coroutineScope = rememberCoroutineScope()

    val innerPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
        end = contentPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    LaunchedEffect(displayCategories, initialCategoryId, initialSelectionApplied) {
        if (initialSelectionApplied || displayCategories.isEmpty()) {
            return@LaunchedEffect
        }
        selectedCategoryId = displayCategories.firstOrNull { it.id == initialCategoryId }?.id ?: NO_ID
        initialSelectionApplied = true
    }

    LaunchedEffect(displayCategories, activeCategoryId) {
        if (displayCategories.isNotEmpty() && displayCategories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = activeCategoryId
        }
    }

    LaunchedEffect(activeCategoryId) {
        val previousActiveCategoryId = lastActiveCategoryId
        if (previousActiveCategoryId == activeCategoryId) {
            return@LaunchedEffect
        }
        lastActiveCategoryId = activeCategoryId
        if (previousActiveCategoryId == null) {
            return@LaunchedEffect
        }
        val nextGeneration = activeChildOverrideGeneration + 1
        activeChildOverrideGeneration = nextGeneration
        childTopBarOverrideState = null
    }

    LaunchedEffect(displayCategories, activeCategoryId) {
        val targetPage = displayCategories.indexOfFirst { it.id == activeCategoryId }
            .takeIf { it >= 0 }
            ?: return@LaunchedEffect
        if (targetPage == pagerState.currentPage || targetPage >= pagerState.pageCount) {
            return@LaunchedEffect
        }
        if (pagerState.currentPage == 0 && !pagerState.isScrollInProgress && lastActiveCategoryId == null) {
            pagerState.scrollToPage(targetPage)
        } else {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState, displayCategories) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                val categoryId = displayCategories.getOrNull(page)?.id ?: return@collect
                if (selectedCategoryId != categoryId) {
                    selectedCategoryId = categoryId
                }
            }
    }

    val compactTabsState = remember(displayCategories, activeCategoryId) {
        CompactTabsTopBarOverrideState(
            items = displayCategories.map {
                CompactTopBarTabItem(
                    id = it.id,
                    title = if (it.id == NO_ID) allFavouritesLabel else (it.title ?: ""),
                )
            },
            selectedItemId = activeCategoryId,
            onItemSelected = { categoryId ->
                val targetPage = displayCategories.indexOfFirst { it.id == categoryId }
                if (targetPage >= 0) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                }
            },
        )
    }
    val effectiveChildTopBarOverrideState = childTopBarOverrideState.takeIf {
        !displayedUiState.isLoading &&
            !displayedUiState.isEmpty &&
            childTopBarOverrideGeneration == activeChildOverrideGeneration &&
            (it as? org.skepsun.kototoro.main.ui.compose.ContentSelectionTopBarOverrideState) != null
    }

    val favoritesTopBarOverrideState = remember(
        compactTabsState,
        effectiveChildTopBarOverrideState,
    ) {
        LayeredTopBarOverrideState(
            tabsState = compactTabsState,
            contextualOverrideState = effectiveChildTopBarOverrideState,
            keepTabsExpandedWhenCollapsed = true,
        )
    }

    LaunchedEffect(displayedUiState.isLoading, favoritesTopBarOverrideState) {
        if (displayedUiState.isLoading) return@LaunchedEffect
        onTopBarOverrideChanged(favoritesTopBarOverrideState)
    }

    val hazeState = remember { HazeState() }
    val useBackgroundHaze = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (displayedUiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (displayedUiState.isEmpty) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_empty_favourites), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.text_empty_holder_primary), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.you_have_not_favourites_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (useBackgroundHaze) Modifier.hazeSource(hazeState) else Modifier),
                userScrollEnabled = displayCategories.size > 1,
                key = { page -> displayCategories.getOrNull(page)?.id ?: page },
            ) { page ->
                val category = displayCategories.getOrNull(page) ?: return@HorizontalPager
                KototoroFavoritesListScreen(
                    categoryId = category.id,
                    appRouter = appRouter,
                    contentPadding = innerPadding,
                    onNavigateToDetails = onNavigateToDetails,
                    sharedTransitionEnabled = pagerState.currentPage == page,
                    isActivePage = pagerState.currentPage == page,
                    onTopBarOverrideChanged = { overrideState ->
                        if (pagerState.currentPage == page) {
                            childTopBarOverrideState = overrideState
                            childTopBarOverrideGeneration = activeChildOverrideGeneration
                        }
                    },
                    onFilterRailOverrideChanged = {},
                )
            }

            AnimatedVisibility(
                visible = showMigrationPanel,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SourceMigrationPanel(
                    onDismiss = { viewModel.hideMigrationPanel() },
                )
            }
        }
    }
}
