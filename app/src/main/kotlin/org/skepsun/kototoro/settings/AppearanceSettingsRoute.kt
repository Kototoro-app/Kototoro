package org.skepsun.kototoro.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.AppFontPreset
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.ColorScheme
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.prefs.ScreenshotsPolicy
import org.skepsun.kototoro.core.prefs.SearchSuggestionType
import org.skepsun.kototoro.core.prefs.TabletUiMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.prefs.normalized
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.util.ActivityRecreationHandle
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.util.ext.getLocalesConfig
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.core.util.ext.toList
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.util.toTitleCase
import org.skepsun.kototoro.settings.compose.AppearanceSettingsOptions
import org.skepsun.kototoro.settings.compose.AppearanceSettingsPage
import org.skepsun.kototoro.settings.compose.AppearanceSettingsScreen
import org.skepsun.kototoro.settings.compose.AppearanceSettingsUiState
import org.skepsun.kototoro.settings.compose.PanoramaEffectPreset
import org.skepsun.kototoro.settings.compose.PanoramaLayoutMode
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.resolvePanoramaEffectPreset
import org.skepsun.kototoro.settings.protect.ProtectSetupActivity

@Composable
fun AppearanceSettingsRoute(
    page: AppearanceSettingsPage = AppearanceSettingsPage.OVERVIEW,
    settings: AppSettings,
    activityRecreationHandle: ActivityRecreationHandle,
    appShortcutManager: AppShortcutManager,
    sourcePresetsRepository: SourcePresetsRepository,
    onOpenNavConfig: () -> Unit,
    onOpenPanoramaSettings: () -> Unit,
    onOpenProtectSetup: () -> Unit,
    onOpenBadgesSettings: () -> Unit = {},
    onOpenSearchFiltersSettings: () -> Unit = {},
    onOpenNavigationSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val coordinator = remember(context, settings, activityRecreationHandle) {
        AppearanceSettingsCoordinator(
            context = context,
            settings = settings,
            activityRecreationHandle = activityRecreationHandle,
            onOpenProtectSetup = onOpenProtectSetup,
        )
    }

    val colorScheme = settings.observeAsState(AppSettings.KEY_COLOR_THEME) { colorScheme }.value
    val interfaceStyle = settings.observeAsState(AppSettings.KEY_INTERFACE_STYLE) { interfaceStyle }.value
    val theme = settings.observeAsState(AppSettings.KEY_THEME) { theme }.value
    val backgroundStyle = settings.observeAsState(AppSettings.KEY_BACKGROUND_STYLE) { backgroundStyle }.value
    val isAmoledTheme = settings.observeAsState(AppSettings.KEY_THEME_AMOLED) { isAmoledTheme }.value
    val appFontPreset = settings.observeAsState(AppSettings.KEY_APP_FONT_PRESET) { appFontPreset }.value
    val expressiveAppFontPreset =
        settings.observeAsState(AppSettings.KEY_EXPRESSIVE_APP_FONT_PRESET) { expressiveAppFontPreset }.value
    val isReducedVisualEffectsEnabled =
        settings.observeAsState(AppSettings.KEY_REDUCED_VISUAL_EFFECTS) { isReducedVisualEffectsEnabled }.value
    val tabletUiMode = settings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }.value
    val appLocale = settings.observeAsState(AppSettings.KEY_APP_LOCALE) { appLocales.toLanguageTags() }.value
    val loadingCircleStyle = settings.observeAsState(AppSettings.KEY_LOADING_CIRCLE_STYLE) { loadingCircleStyle }.value
    val popupRadius = settings.observeAsState(AppSettings.KEY_POPUP_RADIUS) { popupRadius }.value
    val persistedHomeHeroMode = settings.observeAsState(
        AppSettings.KEY_HOME_HERO_MODE,
        AppSettings.KEY_HOME_HERO_STYLE,
    ) { homeHeroMode }.value
    var homeHeroMode by remember(persistedHomeHeroMode) {
        mutableStateOf(persistedHomeHeroMode)
    }
    val homeHeroBackground = settings.observeAsState(
        AppSettings.KEY_HOME_HERO_BACKGROUND,
        AppSettings.KEY_HOME_HERO_STYLE,
    ) { homeHeroBackground }.value
    val homeHeroContentLayout = settings.observeAsState(
        AppSettings.KEY_HOME_HERO_CONTENT_LAYOUT,
        AppSettings.KEY_HOME_HERO_STYLE,
    ) { homeHeroContentLayout }.value
    val listMode = settings.observeAsState(AppSettings.KEY_LIST_MODE) { listMode }.value
    val gridSize = settings.observeAsState(AppSettings.KEY_GRID_SIZE) { gridSize }.value
    val railAnimationIntensityPercent =
        settings.observeAsState(AppSettings.KEY_RAIL_ANIMATION_INTENSITY) { railAnimationIntensityPercent }.value
    val isQuickFilterEnabled = settings.observeAsState(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled }.value
    val isTabletListPreviewEnabled =
        settings.observeAsState(AppSettings.KEY_TABLET_LIST_PREVIEW) { isTabletListPreviewEnabled }.value
    val isTabletListFilterPanelDefaultOpen = settings.observeAsState(
        AppSettings.KEY_TABLET_LIST_FILTER_PANEL_DEFAULT,
    ) { isTabletListFilterPanelDefaultOpen }.value
    val progressIndicatorMode = settings.observeAsState(AppSettings.KEY_PROGRESS_INDICATORS) { progressIndicatorMode }.value
    val mangaListBadges = settings.observeAsState(AppSettings.KEY_MANGA_LIST_BADGES) { mangaListBadges }.value
    val isDescriptionExpanded = settings.observeAsState(AppSettings.KEY_COLLAPSE_DESCRIPTION) { isDescriptionExpanded }.value
    val isPanoramaCoverEnabled = settings.observeAsState(AppSettings.KEY_PANORAMA_ENABLED) { isPanoramaCoverEnabled }.value
    val panoramaCoverBlur = settings.observeAsState(AppSettings.KEY_PANORAMA_BLUR) { panoramaCoverBlur }.value
    val panoramaTransitionRange =
        settings.observeAsState(AppSettings.KEY_PANORAMA_TRANSITION_INTENSITY) { panoramaTransitionRange }.value
    val panoramaTopOpacity =
        settings.observeAsState(AppSettings.KEY_PANORAMA_TOP_OPACITY) { panoramaTopOpacity }.value
    val isPanoramaCoverAnimationEnabled =
        settings.observeAsState(AppSettings.KEY_PANORAMA_ANIMATION_ENABLED) { isPanoramaCoverAnimationEnabled }.value
    val panoramaLayoutMode = settings.observeAsState(
        AppSettings.KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT,
    ) {
        if (isDetailsPanoramaLimitedToInfoCardMidpoint) {
            PanoramaLayoutMode.HALF_SCREEN
        } else {
            PanoramaLayoutMode.FULL_SCREEN
        }
    }.value
    val isPagesTabEnabled = settings.observeAsState(AppSettings.KEY_PAGES_TAB) { isPagesTabEnabled }.value
    val isDetailsTranslateButtonVisible =
        settings.observeAsState(AppSettings.KEY_DETAILS_TRANSLATE_BUTTON) { isDetailsTranslateButtonVisible }.value
    val isModernDetailsDockEnabled =
        settings.observeAsState(AppSettings.KEY_MODERN_DETAILS_DOCK) { isModernDetailsDockEnabled }.value
    val defaultDetailsTab =
        settings.observeAsState(AppSettings.KEY_PAGES_TAB, AppSettings.KEY_DETAILS_TAB) { defaultDetailsTab }.value
    val searchSuggestionTypes =
        settings.observeAsState(AppSettings.KEY_SEARCH_SUGGESTION_TYPES) { searchSuggestionTypes }.value
    val mainNavItems = settings.observeAsState(AppSettings.KEY_NAV_MAIN) { mainNavItems }.value
    val listToDetailsTransition =
        settings.observeAsState(AppSettings.KEY_LIST_TO_DETAILS_TRANSITION) { listToDetailsTransition }.value
    val isShowLanguagePresetFilter =
        settings.observeAsState(AppSettings.KEY_SHOW_LANGUAGE_PRESET_FILTER) { isShowLanguagePresetFilter }.value
    val hiddenLanguagePreset =
        settings.observeAsState(AppSettings.KEY_HIDDEN_LANGUAGE_PRESET) { hiddenLanguagePreset ?: "all" }.value
    val isShowContentTypeFilter =
        settings.observeAsState(AppSettings.KEY_SHOW_CONTENT_TYPE_FILTER) { isShowContentTypeFilter }.value
    val hiddenContentType =
        settings.observeAsState(AppSettings.KEY_HIDDEN_CONTENT_TYPE) { hiddenContentType ?: "all" }.value
    val isShowSourceTagFilter =
        settings.observeAsState(AppSettings.KEY_SHOW_SOURCE_TAG_FILTER) { isShowSourceTagFilter }.value
    val hiddenSourceTag =
        settings.observeAsState(AppSettings.KEY_HIDDEN_SOURCE_TAG) { hiddenSourceTag }
            .value
            .let(coordinator::parseHiddenSourceTagSelection)
    val isMainFabEnabled = settings.observeAsState(AppSettings.KEY_MAIN_FAB) { isMainFabEnabled }.value
    val isNavBarPinned = settings.observeAsState(AppSettings.KEY_NAV_PINNED) { isNavBarPinned }.value
    val isNavLabelsVisible = settings.observeAsState(AppSettings.KEY_NAV_LABELS) { isNavLabelsVisible }.value
    val isNavFloating = settings.observeAsState(AppSettings.KEY_NAV_FLOATING) { isNavFloating }.value
    val isNavLayeredSurface =
        settings.observeAsState(AppSettings.KEY_NAV_LAYERED_SURFACE) { isNavLayeredSurface }.value
    val isNavExpressivePillEnabled =
        settings.observeAsState(AppSettings.KEY_NAV_EXPRESSIVE_PILL) { isNavExpressivePillEnabled }.value
    val navHeight = settings.observeAsState(AppSettings.KEY_NAV_HEIGHT) { navHeight }.value
    val navFloatingHeight = settings.observeAsState(AppSettings.KEY_NAV_FLOATING_HEIGHT) { navFloatingHeight }.value
    val isExitConfirmationEnabled =
        settings.observeAsState(AppSettings.KEY_EXIT_CONFIRM) { isExitConfirmationEnabled }.value
    val isDynamicShortcutsEnabled =
        settings.observeAsState(AppSettings.KEY_SHORTCUTS) { isDynamicShortcutsEnabled }.value
    val isAppProtected =
        settings.observeAsState(AppSettings.KEY_APP_PASSWORD) { !appPassword.isNullOrEmpty() }.value
    val screenshotsPolicy =
        settings.observeAsState(AppSettings.KEY_SCREENSHOTS_POLICY) { screenshotsPolicy }.value
    val languagePresetOptions = sourcePresetsRepository.observeAll()
        .map { presets -> coordinator.buildLanguagePresetOptions(presets, hiddenLanguagePreset) }
        .collectAsStateWithLifecycle(
            initialValue = coordinator.buildLanguagePresetOptions(emptyList(), hiddenLanguagePreset),
        )
        .value

    val effectivePanoramaCoverAnimationEnabled =
        isPanoramaCoverAnimationEnabled && !isReducedVisualEffectsEnabled
    val panoramaPreset = resolvePanoramaEffectPreset(
        panoramaLayoutMode,
        panoramaCoverBlur,
        panoramaTransitionRange,
        panoramaTopOpacity,
    )
    val panoramaPresetLabel = context.getString(
        when (panoramaPreset) {
            PanoramaEffectPreset.CLEAR -> R.string.panorama_preset_clear
            PanoramaEffectPreset.BALANCED -> R.string.panorama_preset_balanced
            PanoramaEffectPreset.SOFT -> R.string.panorama_preset_soft
            PanoramaEffectPreset.CUSTOM -> R.string.panorama_preset_custom
        },
    )
    val panoramaLayoutModeLabel = context.getString(
        when (panoramaLayoutMode) {
            PanoramaLayoutMode.FULL_SCREEN -> R.string.panorama_mode_full_screen
            PanoramaLayoutMode.HALF_SCREEN -> R.string.panorama_mode_half_screen
        },
    )
    val panoramaCoverSummary = if (isPanoramaCoverEnabled) {
        context.getString(
            R.string.panorama_settings_entry_summary,
            panoramaLayoutModeLabel,
            panoramaPresetLabel,
            context.getString(
                if (effectivePanoramaCoverAnimationEnabled) {
                    R.string.panorama_animation_on
                } else {
                    R.string.panorama_animation_off
                },
            ),
        )
    } else {
        context.getString(R.string.panorama_settings_disabled)
    }
    val listToDetailsTransitionOptions = listOf(
        SettingsChoiceOption(
            value = ListToDetailsTransition.HERO_EXPAND,
            label = context.getString(R.string.pref_list_to_details_transition_hero),
        ),
        SettingsChoiceOption(
            value = ListToDetailsTransition.LEGACY_SLIDE,
            label = context.getString(R.string.pref_list_to_details_transition_legacy),
        ),
    )

    val backgroundStyleOptions = coordinator.buildBackgroundStyleOptions()
    val effectiveBackgroundStyle = backgroundStyle.takeIf { selected ->
        backgroundStyleOptions.any { it.value == selected }
    } ?: BackgroundStyle.DEFAULT
    val options = AppearanceSettingsOptions(
        colorSchemes = coordinator.buildColorSchemeOptions(interfaceStyle),
        interfaceStyles = coordinator.buildInterfaceStyleOptions(),
        themes = coordinator.buildThemeOptions(),
        backgroundStyles = backgroundStyleOptions,
        fontPresets = coordinator.buildFontPresetOptions(),
        tabletUiModes = coordinator.buildTabletUiModeOptions(),
        appLocales = coordinator.buildLocaleOptions(),
        loadingCircleStyles = coordinator.buildLoadingCircleStyleOptions(),
        popupRadii = coordinator.buildPopupRadiusOptions(),
        homeHeroModes = coordinator.buildHomeHeroModeOptions(),
        homeHeroBackgrounds = coordinator.buildHomeHeroBackgroundOptions(),
        homeHeroContentLayouts = coordinator.buildHomeHeroContentLayoutOptions(),
        listModes = coordinator.buildListModeOptions(),
        progressIndicatorModes = coordinator.buildProgressIndicatorModeOptions(),
        badgeOptions = coordinator.buildBadgeOptions(),
        bottomRightBadgeOptions = coordinator.buildBottomRightBadgeOptions(),
        mangaListBadges = coordinator.buildMangaListBadgeOptions(),
        detailsTabs = coordinator.buildDetailsTabOptions(),
        searchSuggestionTypes = coordinator.buildSearchSuggestionTypeOptions(),
        listToDetailsTransitionOptions = listToDetailsTransitionOptions,
        languagePresets = languagePresetOptions,
        contentTypes = coordinator.buildContentTypeOptions(),
        sourceTags = coordinator.buildSourceTagOptions(),
        screenshotsPolicies = coordinator.buildScreenshotsPolicyOptions(),
    )

    val uiState = AppearanceSettingsUiState(
        navSummary = coordinator.buildNavSummary(mainNavItems),
        interfaceStyle = interfaceStyle,
        colorScheme = colorScheme,
        theme = theme,
        backgroundStyle = effectiveBackgroundStyle,
        isAmoledTheme = isAmoledTheme,
        appFontPreset = appFontPreset,
        expressiveAppFontPreset = expressiveAppFontPreset,
        tabletUiMode = tabletUiMode,
        appLocale = appLocale,
        loadingCircleStyle = loadingCircleStyle,
        popupRadius = popupRadius,
        homeHeroMode = homeHeroMode,
        homeHeroBackground = homeHeroBackground,
        homeHeroContentLayout = homeHeroContentLayout,
        listMode = listMode,
        gridSize = gridSize,
        railAnimationIntensityPercent = railAnimationIntensityPercent,
        isRailAnimationSettingsEnabled = !isReducedVisualEffectsEnabled,
        isQuickFilterEnabled = isQuickFilterEnabled,
        isTabletListPreviewEnabled = isTabletListPreviewEnabled,
        isTabletListFilterPanelDefaultOpen = isTabletListFilterPanelDefaultOpen,
        progressIndicatorMode = progressIndicatorMode,
        badgesTopLeft = settings.observeAsState(AppSettings.KEY_BADGES_TOP_LEFT) { badgesTopLeft }.value,
        badgesTopRight = settings.observeAsState(AppSettings.KEY_BADGES_TOP_RIGHT) { badgesTopRight }.value,
        badgesBottomLeft = settings.observeAsState(AppSettings.KEY_BADGES_BOTTOM_LEFT) { badgesBottomLeft }.value,
        badgesBottomRight = settings.observeAsState(AppSettings.KEY_BADGES_BOTTOM_RIGHT) { badgesBottomRight }.value,
        mangaListBadges = mangaListBadges,
        isDescriptionExpanded = isDescriptionExpanded,
        isPanoramaCoverEnabled = isPanoramaCoverEnabled,
        panoramaCoverSummary = panoramaCoverSummary,
        isPagesTabEnabled = isPagesTabEnabled,
        isDetailsTranslateButtonVisible = isDetailsTranslateButtonVisible,
        isModernDetailsDockEnabled = isModernDetailsDockEnabled,
        defaultDetailsTab = defaultDetailsTab,
        searchSuggestionTypes = searchSuggestionTypes,
        listToDetailsTransition = listToDetailsTransition,
        isListToDetailsTransitionSettingsEnabled = !isReducedVisualEffectsEnabled,
        isShowLanguagePresetFilter = isShowLanguagePresetFilter,
        hiddenLanguagePreset = hiddenLanguagePreset,
        isShowContentTypeFilter = isShowContentTypeFilter,
        hiddenContentType = hiddenContentType,
        isShowSourceTagFilter = isShowSourceTagFilter,
        hiddenSourceTag = hiddenSourceTag,
        isMainFabEnabled = isMainFabEnabled,
        isNavBarPinned = isNavBarPinned,
        isNavLabelsVisible = isNavLabelsVisible,
        isNavFloating = isNavFloating,
        isNavLayeredSurface = isNavLayeredSurface,
        isNavExpressivePillEnabled = isNavExpressivePillEnabled,
        navHeight = navHeight,
        navFloatingHeight = navFloatingHeight,
        isExitConfirmationEnabled = isExitConfirmationEnabled,
        isDynamicShortcutsVisible = appShortcutManager.isDynamicShortcutsAvailable(),
        isDynamicShortcutsEnabled = isDynamicShortcutsEnabled,
        isAppProtected = isAppProtected,
        screenshotsPolicy = screenshotsPolicy,
    )

    AppearanceSettingsScreen(
        page = page,
        state = uiState,
        options = options,
        emptySelectionText = context.getString(R.string.none),
        onInterfaceStyleChange = { coordinator.updateAndRestart(coroutineScope) { settings.interfaceStyle = it } },
        onColorSchemeChange = { coordinator.updateAndRestart(coroutineScope) { settings.colorScheme = it } },
        onThemeChange = coordinator::updateTheme,
        onBackgroundStyleChange = { coordinator.updateAndRestart(coroutineScope) { settings.backgroundStyle = it } },
        onAmoledThemeChange = { coordinator.updateAndRestart(coroutineScope) { settings.isAmoledTheme = it } },
        onAppFontPresetChange = {
            coordinator.updateAndRestart(coroutineScope) { settings.appFontPreset = it }
        },
        onExpressiveAppFontPresetChange = {
            coordinator.updateAndRestart(coroutineScope) { settings.expressiveAppFontPreset = it }
        },
        onTabletUiModeChange = { settings.tabletUiMode = it },
        onAppLocaleChange = coordinator::updateAppLocale,
        onLoadingCircleStyleChange = { coordinator.updateAndRestart(coroutineScope) { settings.loadingCircleStyle = it } },
        onPopupRadiusChange = { coordinator.updateAndRestart(coroutineScope) { settings.popupRadius = it } },
        onHomeHeroModeChange = {
            homeHeroMode = it
            settings.homeHeroMode = it
        },
        onHomeHeroBackgroundChange = { settings.homeHeroBackground = it },
        onHomeHeroContentLayoutChange = { settings.homeHeroContentLayout = it },
        onListModeChange = { settings.listMode = it },
        onGridSizeChange = { settings.gridSize = it },
        onRailAnimationIntensityChange = { settings.railAnimationIntensityPercent = it },
        onQuickFilterChange = { settings.isQuickFilterEnabled = it },
        onTabletListPreviewChange = { settings.isTabletListPreviewEnabled = it },
        onTabletListFilterPanelDefaultChange = { settings.isTabletListFilterPanelDefaultOpen = it },
        onProgressIndicatorModeChange = { settings.progressIndicatorMode = it },
        onBadgesTopLeftChange = { settings.badgesTopLeft = it },
        onBadgesTopRightChange = { settings.badgesTopRight = it },
        onBadgesBottomLeftChange = { settings.badgesBottomLeft = it },
        onBadgesBottomRightChange = { settings.badgesBottomRight = it },
        onMangaListBadgesChange = { settings.mangaListBadges = it },
        onDescriptionExpandedChange = { settings.isDescriptionExpanded = it },
        onPanoramaCoverEnabledChange = { settings.isPanoramaCoverEnabled = it },
        onPanoramaSettingsClick = onOpenPanoramaSettings,
        onPagesTabEnabledChange = { settings.isPagesTabEnabled = it },
        onDetailsTranslateButtonVisibleChange = { settings.isDetailsTranslateButtonVisible = it },
        onModernDetailsDockEnabledChange = { settings.isModernDetailsDockEnabled = it },
        onDefaultDetailsTabChange = { settings.defaultDetailsTab = it },
        onSearchSuggestionTypesChange = { settings.searchSuggestionTypes = it },
        onNavConfigClick = onOpenNavConfig,
        onListToDetailsTransitionChange = { settings.listToDetailsTransition = it },
        onShowLanguagePresetFilterChange = { settings.isShowLanguagePresetFilter = it },
        onHiddenLanguagePresetChange = { settings.hiddenLanguagePreset = it },
        onShowContentTypeFilterChange = { settings.isShowContentTypeFilter = it },
        onHiddenContentTypeChange = { settings.hiddenContentType = it },
        onShowSourceTagFilterChange = { settings.isShowSourceTagFilter = it },
        onHiddenSourceTagChange = { selection ->
            settings.hiddenSourceTag = selection
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",")
        },
        onMainFabChange = { settings.isMainFabEnabled = it },
        onNavPinnedChange = { settings.isNavBarPinned = it },
        onNavLabelsVisibleChange = { settings.isNavLabelsVisible = it },
        onNavFloatingChange = { settings.isNavFloating = it },
        onNavLayeredSurfaceChange = { settings.isNavLayeredSurface = it },
        onNavExpressivePillChange = { settings.isNavExpressivePillEnabled = it },
        onNavHeightChange = { settings.navHeight = it },
        onNavFloatingHeightChange = { settings.navFloatingHeight = it },
        onExitConfirmationChange = { settings.isExitConfirmationEnabled = it },
        onDynamicShortcutsChange = { settings.isDynamicShortcutsEnabled = it },
        onAppProtectionChange = { coordinator.updateAppProtection(it) },
        onScreenshotsPolicyChange = { settings.screenshotsPolicy = it },
        onBadgesSettingsClick = onOpenBadgesSettings,
        onSearchFiltersSettingsClick = onOpenSearchFiltersSettings,
        onNavigationSettingsClick = onOpenNavigationSettings,
    )
}

private class AppearanceSettingsCoordinator(
    private val context: Context,
    private val settings: AppSettings,
    private val activityRecreationHandle: ActivityRecreationHandle,
    private val onOpenProtectSetup: () -> Unit,
) {

    fun updateTheme(value: Int) {
        settings.theme = value
        AppCompatDelegate.setDefaultNightMode(value)
    }

    fun updateAppLocale(languageTags: String) {
        val locales = LocaleListCompat.forLanguageTags(languageTags)
        settings.appLocales = locales
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun updateAppProtection(isEnabled: Boolean) {
        if (isEnabled) {
            onOpenProtectSetup()
        } else {
            settings.appPassword = null
        }
    }

    fun updateAndRestart(scope: CoroutineScope, block: () -> Unit) {
        block()
        scope.launch {
            delay(400)
            activityRecreationHandle.recreateAll()
        }
    }

    fun buildNavSummary(items: List<NavItem>): String {
        return items.joinToString { context.getString(it.title) }
    }

    fun buildColorSchemeOptions(interfaceStyle: InterfaceStyle): List<SettingsChoiceOption<ColorScheme>> {
        return ColorScheme.getAvailableList()
            .filter { interfaceStyle == InterfaceStyle.IOS || it != ColorScheme.IOS }
            .map {
                SettingsChoiceOption(
                    value = it,
                    label = context.getString(it.titleResId),
                )
            }
    }

    fun buildInterfaceStyleOptions(): List<SettingsChoiceOption<InterfaceStyle>> {
        return InterfaceStyle.selectableEntries.map {
            SettingsChoiceOption(
                value = it,
                label = context.getString(it.titleResId),
            )
        }
    }

    fun buildThemeOptions(): List<SettingsChoiceOption<Int>> {
        val labels = context.resources.getStringArray(R.array.themes)
        val values = context.resources.getStringArray(R.array.values_theme).map { it.toInt() }
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildBackgroundStyleOptions(): List<SettingsChoiceOption<BackgroundStyle>> {
        return BackgroundStyle.selectableEntries.map { style ->
            SettingsChoiceOption(style, context.getString(style.titleResId))
        }
    }

    fun buildFontPresetOptions(): List<SettingsChoiceOption<AppFontPreset>> {
        return listOf(
            SettingsChoiceOption(AppFontPreset.SYSTEM, context.getString(R.string.font_preset_system)),
            SettingsChoiceOption(AppFontPreset.ROBOTO, "Roboto"),
            SettingsChoiceOption(AppFontPreset.ROBOTO_FLEX, "Roboto Flex"),
            SettingsChoiceOption(AppFontPreset.GOOGLE_SANS, "Google Sans"),
            SettingsChoiceOption(AppFontPreset.NOTO_SANS, context.getString(R.string.font_preset_noto_sans)),
            SettingsChoiceOption(AppFontPreset.INTER, context.getString(R.string.font_preset_inter)),
            SettingsChoiceOption(AppFontPreset.ALIMAMA_FANG_YUAN_TI_VF, context.getString(R.string.font_preset_alimama_fang_yuan_ti_vf)),
            SettingsChoiceOption(AppFontPreset.SARASA_GOTHIC, context.getString(R.string.font_preset_sarasa_gothic)),
            SettingsChoiceOption(AppFontPreset.LXGW_WENKAI, context.getString(R.string.font_preset_lxgw_wenkai)),
            SettingsChoiceOption(AppFontPreset.NOTO_SANS_CJK_SC, context.getString(R.string.font_preset_noto_sans_cjk_sc)),
            SettingsChoiceOption(AppFontPreset.SOURCE_HAN_SERIF_SC, context.getString(R.string.font_preset_source_han_serif_sc)),
            SettingsChoiceOption(
                AppFontPreset.GEN_RYU_MIN_TW_S2T,
                context.getString(R.string.font_preset_gen_ryu_min_tw_s2t),
            ),
            SettingsChoiceOption(
                AppFontPreset.KAI_GEN_GOTHIC_TW_S2T,
                context.getString(R.string.font_preset_kai_gen_gothic_tw_s2t),
            ),
        )
    }

    fun buildTabletUiModeOptions(): List<SettingsChoiceOption<TabletUiMode>> {
        return listOf(
            SettingsChoiceOption(TabletUiMode.DISABLED, context.getString(R.string.tablet_ui_mode_disabled)),
            SettingsChoiceOption(TabletUiMode.RELAXED, context.getString(R.string.tablet_ui_mode_relaxed)),
            SettingsChoiceOption(TabletUiMode.STRICT, context.getString(R.string.tablet_ui_mode_strict)),
        )
    }

    fun buildLocaleOptions(): List<SettingsChoiceOption<String>> {
        val locales = context.getLocalesConfig()
            .toList()
            .sortedWithSafe(LocaleComparator())
        return buildList {
            add(SettingsChoiceOption("", context.getString(R.string.follow_system)))
            locales.forEach { locale ->
                add(
                    SettingsChoiceOption(
                        value = locale.toLanguageTag(),
                        label = locale.getDisplayName(locale).toTitleCase(locale),
                    ),
                )
            }
        }
    }

    fun buildLoadingCircleStyleOptions(): List<SettingsChoiceOption<AppSettings.LoadingCircleStyle>> {
        val labels = context.resources.getStringArray(R.array.loading_circle_styles)
        return AppSettings.LoadingCircleStyle.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildPopupRadiusOptions(): List<SettingsChoiceOption<Int>> {
        val labels = context.resources.getStringArray(R.array.popup_radius)
        val values = context.resources.getStringArray(R.array.values_popup_radius).map { it.toInt() }
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildListModeOptions(): List<SettingsChoiceOption<ListMode>> {
        val labels = context.resources.getStringArray(R.array.list_modes)
        return ListMode.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildHomeHeroModeOptions(): List<SettingsChoiceOption<HomeHeroMode>> {
        val labels = context.resources.getStringArray(R.array.home_hero_modes)
        return HomeHeroMode.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildHomeHeroBackgroundOptions(): List<SettingsChoiceOption<HomeHeroBackground>> {
        val labels = context.resources.getStringArray(R.array.home_hero_backgrounds)
        return HomeHeroBackground.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildHomeHeroContentLayoutOptions(): List<SettingsChoiceOption<HomeHeroContentLayout>> {
        val labels = context.resources.getStringArray(R.array.home_hero_content_layouts)
        return HomeHeroContentLayout.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildProgressIndicatorModeOptions(): List<SettingsChoiceOption<ProgressIndicatorMode>> {
        val labels = context.resources.getStringArray(R.array.progress_indicators)
        return ProgressIndicatorMode.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }

    fun buildBadgeOptions(): List<SettingsChoiceOption<String>> {
        val labels = context.resources.getStringArray(R.array.badge_options)
        val values = context.resources.getStringArray(R.array.values_badge_options)
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildBottomRightBadgeOptions(): List<SettingsChoiceOption<String>> {
        val labels = context.resources.getStringArray(R.array.bottom_right_badge_options)
        val values = context.resources.getStringArray(R.array.values_bottom_right_badge_options)
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildMangaListBadgeOptions(): List<SettingsChoiceOption<String>> {
        val labels = context.resources.getStringArray(R.array.list_badges)
        val values = context.resources.getStringArray(R.array.values_list_badges)
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildDetailsTabOptions(): List<SettingsChoiceOption<Int>> {
        val labels = context.resources.getStringArray(R.array.details_tabs)
        val values = context.resources.getStringArray(R.array.details_tabs_values).map { it.toInt() }
        return labels.zip(values).map { (label, value) -> SettingsChoiceOption(value, label) }
    }

    fun buildSearchSuggestionTypeOptions(): List<SettingsChoiceOption<SearchSuggestionType>> {
        return SearchSuggestionType.entries.map {
            SettingsChoiceOption(
                value = it,
                label = context.getString(it.titleResId),
            )
        }
    }

    fun buildLanguagePresetOptions(
        presets: List<SourcePreset>,
        selectedValue: String? = null,
    ): List<SettingsChoiceOption<String>> {
        return buildList {
            add(SettingsChoiceOption("all", context.getString(R.string.all)))
            presets.forEach { preset ->
                add(SettingsChoiceOption(preset.id.toString(), preset.title))
            }
            if (selectedValue != null && selectedValue != "all" && none { it.value == selectedValue }) {
                add(SettingsChoiceOption(selectedValue, context.getString(R.string.loading_)))
            }
        }
    }

    fun buildContentTypeOptions(): List<SettingsChoiceOption<String>> {
        return BrowseGroupTab.getAllTabs().map {
            SettingsChoiceOption(
                value = it.id,
                label = context.getString(it.titleRes),
            )
        }
    }

    fun buildSourceTagOptions(): List<SettingsChoiceOption<String>> {
        return buildList {
            SourceTag.quickFilterEntries.forEach { tag ->
                add(
                    SettingsChoiceOption(
                        value = tag.name,
                        label = context.getString(tag.titleRes),
                    ),
                )
            }
        }
    }

    fun parseHiddenSourceTagSelection(raw: String?): Set<String> {
        if (raw.isNullOrBlank() || raw == "all") {
            return emptySet()
        }
        return SourceTag
            .sanitizeQuickFilterSelection(
                raw.split(',')
                    .mapNotNull { item ->
                        val trimmed = item.trim()
                        when {
                            trimmed.isEmpty() || trimmed == "all" -> null
                            else -> SourceTag.entries.firstOrNull { it.name == trimmed || it.id == trimmed }
                        }
                    }
                    .toSet(),
            )
            .mapTo(linkedSetOf()) { it.name }
    }

    fun buildScreenshotsPolicyOptions(): List<SettingsChoiceOption<ScreenshotsPolicy>> {
        val labels = context.resources.getStringArray(R.array.screenshots_policy)
        return ScreenshotsPolicy.entries.mapIndexed { index, value ->
            SettingsChoiceOption(value = value, label = labels[index])
        }
    }
}
