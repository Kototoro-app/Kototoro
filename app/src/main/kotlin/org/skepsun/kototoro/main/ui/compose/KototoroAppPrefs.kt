package org.skepsun.kototoro.main.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.prefs.TabletUiMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.widgets.resolveNavBarHeight
import org.skepsun.kototoro.list.domain.ListSortOrder

/**
 * State holder for the app-settings-derived read-only flows consumed by
 * [rememberKototoroAppPrefs]'s owner (KototoroApp). Keeping the
 * observeAsState declarations here hoists the settings subscriptions out
 * of the app-shell composable without changing their lifecycle (each
 * flow is still remembered and lifecycle-aware via [observeAsState]).
 */
internal class KototoroAppPrefs internal constructor(
    val navigationPrefs: State<KototoroNavigationPrefs>,
    val displayPrefs: State<KototoroDisplayPrefs>,
    val filterVisibilityPrefs: State<KototoroFilterVisibilityPrefs>,
    val detailsTransitionStyle: State<ListToDetailsTransition>,
    val isReducedVisualEffectsEnabled: State<Boolean>,
    val globalTagBlacklist: State<Set<String>>,
    val isNavBarPinned: State<Boolean>,
    val tabletUiMode: State<TabletUiMode>,
    val mainNavItems: State<List<NavItem>>,
    val isMainFabEnabled: State<Boolean>,
    val sidekickPosition: State<SpaceSwitcherPosition>,
    val globalFavoritesSortOrder: State<ListSortOrder>,
    val showAllUpdates: State<Boolean>,
    val feedLimit: State<Int>,
    val exitConfirmationEnabled: State<Boolean>,
)

@Composable
internal fun rememberKototoroAppPrefs(appSettings: AppSettings): KototoroAppPrefs {
    val navigationPrefs = appSettings.observeAsState(
        AppSettings.KEY_NAV_FLOATING,
        AppSettings.KEY_NAV_LAYERED_SURFACE,
        AppSettings.KEY_NAV_HEIGHT,
        AppSettings.KEY_NAV_FLOATING_HEIGHT,
    ) {
        KototoroNavigationPrefs(
            isFloating = isNavFloating,
            isLayeredSurface = isNavLayeredSurface,
            adjacentFabSize = resolveNavBarHeight(
                isFloating = isNavFloating,
                navHeight = navHeight,
                navFloatingHeight = navFloatingHeight,
            ),
        )
    }
    val displayPrefs = appSettings.observeAsState(
        AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
        AppSettings.KEY_LIST_MODE,
        AppSettings.KEY_LIST_MODE_BROWSE,
        AppSettings.KEY_GRID_SIZE,
        AppSettings.KEY_POPUP_RADIUS,
        AppSettings.KEY_BROWSE_TRACKING_RECOMMENDATIONS,
        AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS,
    ) {
        KototoroDisplayPrefs(
            activeSourcePresetId = activeSourcePresetId,
            listMode = listMode,
            browseListMode = browseListMode,
            gridSize = gridSize,
            cornerRadius = cornerRadius,
            isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
            isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
        )
    }
    val filterVisibilityPrefs = appSettings.observeAsState(
        AppSettings.KEY_SHOW_LANGUAGE_PRESET_FILTER,
        AppSettings.KEY_SHOW_CONTENT_TYPE_FILTER,
        AppSettings.KEY_SHOW_SOURCE_TAG_FILTER,
    ) {
        KototoroFilterVisibilityPrefs(
            isLanguagePresetFilterVisible = isShowLanguagePresetFilter,
            isContentTypeFilterVisible = isShowContentTypeFilter,
            isSourceTagFilterVisible = isShowSourceTagFilter,
        )
    }
    val detailsTransitionStyle = appSettings.observeAsState(
        AppSettings.KEY_LIST_TO_DETAILS_TRANSITION,
    ) {
        listToDetailsTransition
    }
    val isReducedVisualEffectsEnabled = appSettings.observeAsState(
        AppSettings.KEY_REDUCED_VISUAL_EFFECTS,
    ) {
        isReducedVisualEffectsEnabled
    }
    val globalTagBlacklist = appSettings.observeAsState(
        AppSettings.KEY_GLOBAL_TAG_BLACKLIST,
    ) {
        this.globalTagBlacklist
    }
    val isNavBarPinned = appSettings.observeAsState(AppSettings.KEY_NAV_PINNED) { isNavBarPinned }
    val tabletUiMode = appSettings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
    val mainNavItems = appSettings.observeAsState(AppSettings.KEY_NAV_MAIN) { mainNavItems }
    val isMainFabEnabled = appSettings.observeAsState(AppSettings.KEY_MAIN_FAB) { isMainFabEnabled }
    val sidekickPosition = appSettings.observeAsState(AppSettings.KEY_SPACE_SWITCHER_POSITION) {
        spaceSwitcherPosition
    }
    val globalFavoritesSortOrder = appSettings.observeAsState(keys = arrayOf(AppSettings.KEY_FAVORITES_ORDER)) {
        allFavoritesSortOrder
    }
    val showAllUpdates = appSettings.observeAsState(keys = arrayOf(AppSettings.KEY_SHOW_ALL_UPDATES)) {
        showAllUpdates
    }
    val feedLimit = appSettings.observeAsState(keys = arrayOf(AppSettings.KEY_FEED_LIMIT)) {
        feedLimit
    }
    val exitConfirmationEnabled = appSettings.observeAsState(
        AppSettings.KEY_EXIT_CONFIRM,
    ) { isExitConfirmationEnabled }
    return KototoroAppPrefs(
        navigationPrefs = navigationPrefs,
        displayPrefs = displayPrefs,
        filterVisibilityPrefs = filterVisibilityPrefs,
        detailsTransitionStyle = detailsTransitionStyle,
        isReducedVisualEffectsEnabled = isReducedVisualEffectsEnabled,
        globalTagBlacklist = globalTagBlacklist,
        isNavBarPinned = isNavBarPinned,
        tabletUiMode = tabletUiMode,
        mainNavItems = mainNavItems,
        isMainFabEnabled = isMainFabEnabled,
        sidekickPosition = sidekickPosition,
        globalFavoritesSortOrder = globalFavoritesSortOrder,
        showAllUpdates = showAllUpdates,
        feedLimit = feedLimit,
        exitConfirmationEnabled = exitConfirmationEnabled,
    )
}
