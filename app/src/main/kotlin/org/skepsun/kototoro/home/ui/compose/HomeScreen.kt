package org.skepsun.kototoro.home.ui.compose

import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.ui.home.HomeScreenLayout
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
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
import org.skepsun.kototoro.core.ui.compose.railPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.ScrollToTopEffect
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.details.ui.compose.rememberPanoramaBackdropPrefs
import org.skepsun.kototoro.home.ui.HomeSummaryState
import org.skepsun.kototoro.parsers.model.Content

import org.skepsun.kototoro.home.ui.compose.hero.HOME_HERO_CARD_HEIGHT
import org.skepsun.kototoro.home.ui.compose.hero.HomeHeroPresentation
import org.skepsun.kototoro.home.ui.compose.hero.HomeHeroSection
import org.skepsun.kototoro.home.ui.compose.hero.buildHomeHeroEntries
import org.skepsun.kototoro.home.ui.compose.sections.HomeHighlightsSections
import org.skepsun.kototoro.home.ui.compose.sections.HomeQuickAction
import org.skepsun.kototoro.home.ui.compose.sections.HomeRailStyle
import org.skepsun.kototoro.home.ui.compose.sections.QuickActionsSection
import androidx.compose.runtime.getValue

@Immutable
private data class HomeScreenPrefs(
    val gridScale: Float,
    val heroMode: HomeHeroMode,
    val heroBackground: HomeHeroBackground,
    val heroContentLayout: HomeHeroContentLayout,
    val historyListMode: ListMode,
    val updatesListMode: ListMode,
    val recommendationsListMode: ListMode,
    val historyGridScale: Float,
    val updatesGridScale: Float,
    val recommendationsGridScale: Float,
    val historyRailRows: Int,
    val updatesRailRows: Int,
    val recommendationsRailRows: Int,
)

@Stable
data class HomeScreenActions(
    val onSettingsClick: () -> Unit,
    val onReaderSettingsClick: () -> Unit,
    val onViewAllRecentClick: () -> Unit,
    val onViewAllUpdatesClick: () -> Unit,
    val onViewAllRecommendationsClick: () -> Unit,
    val onConfigureHistoryClick: () -> Unit,
    val onConfigureUpdatesClick: () -> Unit,
    val onConfigureRecommendationsClick: () -> Unit,
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
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_HOME_HERO_STYLE,
        AppSettings.KEY_HOME_HERO_MODE,
        AppSettings.KEY_HOME_HERO_BACKGROUND,
        AppSettings.KEY_HOME_HERO_CONTENT_LAYOUT,
        AppSettings.KEY_HOME_SECTION_LIST_MODE_HISTORY,
        AppSettings.KEY_HOME_SECTION_LIST_MODE_UPDATES,
        AppSettings.KEY_HOME_SECTION_LIST_MODE_RECOMMENDATIONS,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_HISTORY,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_UPDATES,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_RECOMMENDATIONS,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_HISTORY,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_UPDATES,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_RECOMMENDATIONS,
    ) {
        HomeScreenPrefs(
            gridScale = gridSize / 100f,
            heroMode = homeHeroMode,
            heroBackground = homeHeroBackground,
            heroContentLayout = homeHeroContentLayout,
            historyListMode = homeSectionListModeHistory,
            updatesListMode = homeSectionListModeUpdates,
            recommendationsListMode = homeSectionListModeRecommendations,
            historyGridScale = homeSectionGridSizeHistory / 100f,
            updatesGridScale = homeSectionGridSizeUpdates / 100f,
            recommendationsGridScale = homeSectionGridSizeRecommendations / 100f,
            historyRailRows = homeSectionRailRowsHistory,
            updatesRailRows = homeSectionRailRowsUpdates,
            recommendationsRailRows = homeSectionRailRowsRecommendations,
        )
    }
    val gridScale = screenPrefs.gridScale
    val heroMode = screenPrefs.heroMode
    val heroBackground = screenPrefs.heroBackground
    val heroContentLayout = screenPrefs.heroContentLayout
    val historyRailStyle = remember(
        screenPrefs.historyListMode,
        screenPrefs.historyGridScale,
        screenPrefs.historyRailRows,
    ) {
        HomeRailStyle(
            listMode = screenPrefs.historyListMode,
            posterStyle = screenPrefs.historyListMode.railPosterCardStyle(screenPrefs.historyGridScale),
            railRowsPerPage = screenPrefs.historyRailRows,
            gridScale = screenPrefs.historyGridScale,
        )
    }
    val updatesRailStyle = remember(
        screenPrefs.updatesListMode,
        screenPrefs.updatesGridScale,
        screenPrefs.updatesRailRows,
    ) {
        HomeRailStyle(
            listMode = screenPrefs.updatesListMode,
            posterStyle = screenPrefs.updatesListMode.railPosterCardStyle(screenPrefs.updatesGridScale),
            railRowsPerPage = screenPrefs.updatesRailRows,
            gridScale = screenPrefs.updatesGridScale,
        )
    }
    val recommendationsRailStyle = remember(
        screenPrefs.recommendationsListMode,
        screenPrefs.recommendationsGridScale,
        screenPrefs.recommendationsRailRows,
    ) {
        HomeRailStyle(
            listMode = screenPrefs.recommendationsListMode,
            posterStyle = screenPrefs.recommendationsListMode.railPosterCardStyle(screenPrefs.recommendationsGridScale),
            railRowsPerPage = screenPrefs.recommendationsRailRows,
            gridScale = screenPrefs.recommendationsGridScale,
        )
    }
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
            HomeQuickAction(stringResource(R.string.reader_settings), R.drawable.ic_read, actions.onReaderSettingsClick),
            HomeQuickAction(stringResource(R.string.settings), R.drawable.ic_settings, actions.onSettingsClick),
    )
    val topInset = contentPadding.calculateTopPadding()
    val scrollTopInset = if (heroEntries.isEmpty()) {
        maxOf(topInset, systemBarsPadding.calculateTopPadding()) + 8.dp
    } else {
        0.dp
    }
    val hasHighlights = heroEntries.isNotEmpty() ||
        state.recentHistoryItems.isNotEmpty() || state.recentUpdates.isNotEmpty() ||
        state.recommendations.isNotEmpty() || recentSearches.isNotEmpty()
    HomeScreenLayout(
        hasHero = heroEntries.isNotEmpty(),
        hasHighlights = hasHighlights,
        initialized = state.isInitialized,
        tabletLayoutEnabled = FoldableUtils.shouldUseTabletLayout(context, settings),
        modifier = modifier,
        listState = listState,
        listModifier = Modifier
            .then(if (isTvPresentation) Modifier.focusGroup() else Modifier)
            .nestedScroll(rememberNestedScrollInteropConnection()),
        contentPadding = PaddingValues(
            top = scrollTopInset,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
        horizontalPadding = PaddingValues(
            start = systemBarsPadding.calculateLeftPadding(layoutDirection) + CompactTopBarHorizontalPadding,
            end = systemBarsPadding.calculateRightPadding(layoutDirection) + CompactTopBarHorizontalPadding,
        ),
        estimatedHeroHeight = HOME_HERO_CARD_HEIGHT + topInset + 8.dp,
        heroInsideList = isTvPresentation,
        hero = { heroModifier, layoutClass ->
            HomeHeroSection(
                entries = heroEntries,
                mode = heroMode,
                fixedPresentation = HomeHeroPresentation(heroBackground, heroContentLayout),
                panoramaPrefs = homeHeroPanoramaPrefs,
                onClick = onContentClick,
                topContentInset = topInset + 8.dp,
                autoAdvance = autoAdvanceHero,
                modifier = heroModifier,
                layoutClass = layoutClass,
            )
        },
        greeting = {
            org.skepsun.kototoro.home.ui.compose.sections.HomeGreetingHeaderCard(
                unreadUpdatesCount = state.unreadUpdatesCount,
                onExploreClick = actions.onViewAllRecommendationsClick,
            )
        },
        highlights = {
            HomeHighlightsSections(
                historyItems = state.recentHistoryItems,
                recentHistoryCount = state.recentHistoryCount,
                updateItems = state.recentUpdates,
                unreadUpdatesCount = state.unreadUpdatesCount,
                recommendationItems = state.recommendations,
                recommendationsCount = state.recommendationsCount,
                recentSearches = recentSearches,
                historyStyle = historyRailStyle,
                updatesStyle = updatesRailStyle,
                recommendationsStyle = recommendationsRailStyle,
                onItemClick = onContentClick,
                onViewAllRecentClick = actions.onViewAllRecentClick,
                onViewAllUpdatesClick = actions.onViewAllUpdatesClick,
                onViewAllRecommendationsClick = actions.onViewAllRecommendationsClick,
                onConfigureHistoryClick = actions.onConfigureHistoryClick,
                onConfigureUpdatesClick = actions.onConfigureUpdatesClick,
                onConfigureRecommendationsClick = actions.onConfigureRecommendationsClick,
                onRecentSearchClick = actions.onRecentSearchClick,
            )
        },
        quickActions = { expanded ->
            QuickActionsSection(actions = quickActions, preferredTileWidth = if (expanded) 96.dp else null)
        },
        loading = { HomeLoadingSkeleton(posterStyle = posterStyle) },
    )
}

internal val HOME_SECTION_GAP = org.skepsun.kototoro.core.ui.home.HomeScreenSectionGap
