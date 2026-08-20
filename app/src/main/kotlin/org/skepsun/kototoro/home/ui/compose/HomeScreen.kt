package org.skepsun.kototoro.home.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.details.ui.compose.rememberPanoramaBackdropPrefs
import org.skepsun.kototoro.home.ui.HomeSummaryState
import org.skepsun.kototoro.parsers.model.Content

import org.skepsun.kototoro.home.ui.compose.hero.HomeHeroPresentation
import org.skepsun.kototoro.home.ui.compose.hero.HomeHeroSection
import org.skepsun.kototoro.home.ui.compose.hero.buildHomeHeroEntries
import org.skepsun.kototoro.home.ui.compose.sections.HomeHighlightsSections
import org.skepsun.kototoro.home.ui.compose.sections.HomeQuickAction
import org.skepsun.kototoro.home.ui.compose.sections.QuickActionsSection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Immutable
private data class HomeScreenPrefs(
    val gridScale: Float,
    val listMode: ListMode,
    val heroMode: HomeHeroMode,
    val heroBackground: HomeHeroBackground,
    val heroContentLayout: HomeHeroContentLayout,
)

@Stable
data class HomeScreenActions(
    val onSettingsClick: () -> Unit,
    val onReaderSettingsClick: () -> Unit,
    val onViewAllRecentClick: () -> Unit,
    val onViewAllUpdatesClick: () -> Unit,
    val onViewAllRecommendationsClick: () -> Unit,
    val onRecentSearchClick: (String) -> Unit,
    val onSetupWizardClick: () -> Unit,
    val onManageSourcesClick: () -> Unit,
    val onLibraryOpenClick: () -> Unit,
    val onBookmarksClick: () -> Unit,
    val onLocalClick: () -> Unit,
    val onDownloadsClick: () -> Unit,
    val onRandomClick: () -> Unit,
    val onAutoTranslateClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: HomeSummaryState,
    onContentClick: (Content, Rect?, String?) -> Unit,
    actions: HomeScreenActions,
    isRandomLoading: Boolean,
    modifier: Modifier = Modifier,
    autoAdvanceHero: Boolean = false,
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect {
        listState.scrollToItem(0)
    }
    val layoutDirection = LocalLayoutDirection.current
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_LIST_MODE_HOME,
        AppSettings.KEY_HOME_HERO_STYLE,
        AppSettings.KEY_HOME_HERO_MODE,
        AppSettings.KEY_HOME_HERO_BACKGROUND,
        AppSettings.KEY_HOME_HERO_CONTENT_LAYOUT,
    ) {
        HomeScreenPrefs(
            gridScale = gridSize / 100f,
            listMode = homeListMode,
            heroMode = homeHeroMode,
            heroBackground = homeHeroBackground,
            heroContentLayout = homeHeroContentLayout,
        )
    }
    val gridScale = screenPrefs.gridScale
    val listMode = screenPrefs.listMode
    val heroMode = screenPrefs.heroMode
    val heroBackground = screenPrefs.heroBackground
    val heroContentLayout = screenPrefs.heroContentLayout
    val posterStyle = remember(gridScale) { compactPosterCardStyle(gridScale) }
    val panoramaPrefs = rememberPanoramaBackdropPrefs(settings)
    val homeHeroPanoramaPrefs = remember(panoramaPrefs) {
        panoramaPrefs.copy(isAnimationEnabled = false)
    }
    val recentSearches = remember(state.recentSearches) { state.recentSearches.map { it.query } }
    val heroEntries = remember(
        state.resumeState.content,
        state.resumeState.groupKey,
        state.resumeState.progressPercent,
        state.recentHistoryItems,
        state.recentUpdates,
        state.recommendations,
    ) {
        buildHomeHeroEntries(
            resumeContent = state.resumeState.content,
            resumeGroupKey = state.resumeState.groupKey,
            resumeProgressPercent = state.resumeState.progressPercent,
            historyItems = state.recentHistoryItems,
            updateItems = state.recentUpdates,
            recommendationItems = state.recommendations,
        )
    }
    val quickActions = listOf(
            HomeQuickAction(stringResource(R.string.home_quick_action_wizard), R.drawable.ic_welcome, actions.onSetupWizardClick),
            HomeQuickAction(stringResource(R.string.favourites), R.drawable.ic_heart, actions.onLibraryOpenClick),
            HomeQuickAction(stringResource(R.string.bookmarks), R.drawable.ic_bookmark, actions.onBookmarksClick),
            HomeQuickAction(stringResource(R.string.local_storage), R.drawable.ic_storage, actions.onLocalClick),
            HomeQuickAction(stringResource(R.string.downloads), R.drawable.ic_download, actions.onDownloadsClick),
            HomeQuickAction(stringResource(R.string.random), R.drawable.ic_dice, actions.onRandomClick, !isRandomLoading),
            HomeQuickAction(stringResource(R.string.home_quick_action_extensions), R.drawable.ic_extension, actions.onManageSourcesClick),
            HomeQuickAction(stringResource(R.string.translation_settings), R.drawable.ic_language, actions.onAutoTranslateClick),
            HomeQuickAction(stringResource(R.string.reader_settings), R.drawable.ic_read, actions.onReaderSettingsClick),
            HomeQuickAction(stringResource(R.string.settings), R.drawable.ic_settings, actions.onSettingsClick),
    )
    val topInset = contentPadding.calculateTopPadding()
    val scrollTopInset = if (heroEntries.isEmpty()) {
        maxOf(topInset, systemBarsPadding.calculateTopPadding()) + 8.dp
    } else {
        0.dp
    }
    val estimatedHeroPx = with(density) { (340.dp + topInset).roundToPx() }
    var heroPx by rememberSaveable { mutableIntStateOf(estimatedHeroPx) }
    val heroHeightDp by remember(heroPx, density) {
        derivedStateOf { with(density) { heroPx.toDp() } }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .padding(
                    start = systemBarsPadding.calculateLeftPadding(layoutDirection) + CompactTopBarHorizontalPadding,
                    end = systemBarsPadding.calculateRightPadding(layoutDirection) + CompactTopBarHorizontalPadding,
                ),
            contentPadding = PaddingValues(
                top = scrollTopInset,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(HOME_SECTION_GAP),
        ) {
            val hasHighlights = heroEntries.isNotEmpty() ||
                state.recentHistoryItems.isNotEmpty() ||
                state.recentUpdates.isNotEmpty() ||
                state.recommendations.isNotEmpty() ||
                recentSearches.isNotEmpty()
            if (hasHighlights) {
                if (heroEntries.isNotEmpty()) {
                    item(key = "home_hero_spacer") {
                        Spacer(modifier = Modifier.height(heroHeightDp))
                    }
                }
                item(key = "home_highlights") {
                    HomeHighlightsSections(
                        historyItems = state.recentHistoryItems,
                        recentHistoryCount = state.recentHistoryCount,
                        updateItems = state.recentUpdates,
                        unreadUpdatesCount = state.unreadUpdatesCount,
                        recommendationItems = state.recommendations,
                        recommendationsCount = state.recommendationsCount,
                        recentSearches = recentSearches,
                        posterStyle = posterStyle,
                        listMode = listMode,
                        onItemClick = onContentClick,
                        onViewAllRecentClick = actions.onViewAllRecentClick,
                        onViewAllUpdatesClick = actions.onViewAllUpdatesClick,
                        onViewAllRecommendationsClick = actions.onViewAllRecommendationsClick,
                        onRecentSearchClick = actions.onRecentSearchClick,
                    )
                }
            }
            if (!hasHighlights && !state.isInitialized) {
                item(key = "home_loading_skeleton") {
                    HomeLoadingSkeleton(posterStyle = posterStyle)
                }
            }

            item(key = "home_quick_actions") {
                QuickActionsSection(actions = quickActions)
            }
        }

        if (heroEntries.isNotEmpty()) {
            HomeHeroSection(
                entries = heroEntries,
                mode = heroMode,
                fixedPresentation = HomeHeroPresentation(heroBackground, heroContentLayout),
                panoramaPrefs = homeHeroPanoramaPrefs,
                onClick = onContentClick,
                topContentInset = topInset + 8.dp,
                autoAdvance = autoAdvanceHero,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val scrollOffset = if (listState.firstVisibleItemIndex == 0) {
                            listState.firstVisibleItemScrollOffset.toFloat()
                        } else {
                            heroPx.toFloat()
                        }
                        translationY = -scrollOffset.coerceIn(0f, heroPx.toFloat())
                    }
                    .onGloballyPositioned { coordinates ->
                        val newHeight = coordinates.size.height
                        if (heroPx != newHeight) heroPx = newHeight
                    },
            )
        }
    }
}

internal val HOME_SECTION_GAP = 4.dp

