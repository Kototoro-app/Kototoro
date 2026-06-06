package org.skepsun.kototoro.favourites.ui.compose

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.glass.LocalHazeState
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.container.FavouriteTabModel
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarTabItem
import org.skepsun.kototoro.main.ui.compose.ContentSelectionTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroFavoritesHostRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    initialCategoryId: Long = NO_ID,
    initialCategoryTitle: String? = null,
    onOpenEntityOrganize: (Set<Long>) -> Unit = {},
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    onNavigateToEntityDetails: ((DetailsOrigin, String?) -> Unit)? = null,
    registerFilterCallback: Boolean = true,
    refreshGeneration: Int = 0,
    consumeOrganizeMessages: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    viewModel: FavouritesContainerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mainActivity = LocalContext.current as? MainActivity
    val context = LocalContext.current
    val globalState = viewModel.globalFavoritesState
    val selectedGroupTab by globalState.selectedGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by globalState.selectedSourceTags.collectAsStateWithLifecycle()

    DisposableEffect(mainActivity, globalState, selectedGroupTab, selectedSourceTags, registerFilterCallback) {
        if (!registerFilterCallback) {
            onDispose { }
        } else {
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
        if (registerFilterCallback) {
            mainActivity?.refreshFilters()
        }
    }

    val displayCategories = remember(uiState.categories, initialCategoryId, initialCategoryTitle) {
        val categories = buildList {
            add(FavouriteTabModel(id = NO_ID, title = null))
            uiState.categories.filterTo(this) { it.id != NO_ID }
        }
        if (initialCategoryId == NO_ID || categories.any { it.id == initialCategoryId }) {
            categories
        } else {
            categories + FavouriteTabModel(id = initialCategoryId, title = initialCategoryTitle)
        }
    }
    val initialPage = remember(displayCategories, initialCategoryId) {
        displayCategories.indexOfFirst { it.id == initialCategoryId }.takeIf { it >= 0 } ?: 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { displayCategories.size },
    )
    val coroutineScope = rememberCoroutineScope()
    var initialSelectionApplied by rememberSaveable(initialCategoryId) { mutableStateOf(false) }
    var childTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var childTopBarOverrideGeneration by remember { mutableIntStateOf(-1) }
    var activeChildOverrideGeneration by remember { mutableIntStateOf(0) }
    var lastActiveCategoryId by remember { mutableStateOf<Long?>(null) }
    val allFavouritesLabel = stringResource(R.string.all_favourites)
    val activePage = pagerState.settledPage.coerceIn(0, (displayCategories.size - 1).coerceAtLeast(0))
    val selectedTabsPage = pagerState.targetPage.coerceIn(0, (displayCategories.size - 1).coerceAtLeast(0))
    val activeCategoryId = displayCategories.getOrNull(activePage)?.id

    val innerPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
        end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    LaunchedEffect(displayCategories, initialCategoryId, initialSelectionApplied) {
        if (initialSelectionApplied || displayCategories.isEmpty()) {
            return@LaunchedEffect
        }
        val targetPage = displayCategories.indexOfFirst { it.id == initialCategoryId }.takeIf { it >= 0 } ?: 0
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        initialSelectionApplied = true
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
        activeChildOverrideGeneration += 1
        childTopBarOverrideState = null
    }

    val compactTabsState = remember(displayCategories, selectedTabsPage, allFavouritesLabel) {
        CompactTabsTopBarOverrideState(
            items = displayCategories.map {
                CompactTopBarTabItem(
                    id = it.id,
                    title = if (it.id == NO_ID) allFavouritesLabel else (it.title ?: ""),
                )
            },
            selectedItemId = displayCategories.getOrNull(selectedTabsPage)?.id ?: NO_ID,
            onItemSelected = { categoryId ->
                val targetPage = displayCategories.indexOfFirst { it.id == categoryId }
                if (targetPage >= 0) {
                    coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
                }
            },
        )
    }

    val effectiveChildTopBarOverrideState = childTopBarOverrideState.takeIf {
        !uiState.isLoading &&
            !uiState.isEmpty &&
            childTopBarOverrideGeneration == activeChildOverrideGeneration &&
            it is ContentSelectionTopBarOverrideState
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

    LaunchedEffect(uiState.isLoading, favoritesTopBarOverrideState) {
        if (!uiState.isLoading) {
            onTopBarOverrideChanged(favoritesTopBarOverrideState)
        }
    }

    if (consumeOrganizeMessages) {
        LaunchedEffect(viewModel.organizeMessages) {
            viewModel.organizeMessages.collect { event ->
                event?.consume(
                    FlowCollector { message ->
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    val hazeState = remember { HazeState() }
    val useBackgroundHaze = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (uiState.isEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_empty_favourites),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.text_empty_holder_primary),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.you_have_not_favourites_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (useBackgroundHaze) Modifier.haze(hazeState) else Modifier),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page ->
                        val categoryId = displayCategories.getOrNull(page)?.id ?: page.toLong()
                        "${categoryId}_$refreshGeneration"
                    },
                ) { page ->
                    val category = displayCategories.getOrNull(page) ?: return@HorizontalPager
                    val enabled = page == activePage
                    KototoroFavoritesListScreen(
                        categoryId = category.id,
                        appRouter = appRouter,
                        contentPadding = innerPadding,
                        onNavigateToDetails = onNavigateToDetails,
                        onNavigateToEntityDetails = onNavigateToEntityDetails,
                        onEntityOrganizeSelection = onOpenEntityOrganize,
                        sharedTransitionEnabled = enabled,
                        isActivePage = enabled,
                        onTopBarOverrideChanged = { overrideState ->
                            if (enabled && category.id == activeCategoryId) {
                                childTopBarOverrideState = overrideState
                                childTopBarOverrideGeneration = activeChildOverrideGeneration
                            }
                        },
                        onFilterRailOverrideChanged = {},
                    )
                }
            }

        }
    }
}
