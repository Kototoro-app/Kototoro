package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.AppFontPreset
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.ColorScheme
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.tokens
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.prefs.ScreenshotsPolicy
import org.skepsun.kototoro.core.prefs.SearchSuggestionType
import org.skepsun.kototoro.core.prefs.TabletUiMode

data class AppearanceSettingsUiState(
    val navSummary: String,
    val interfaceStyle: InterfaceStyle,
    val colorScheme: ColorScheme,
    val theme: Int,
    val backgroundStyle: BackgroundStyle,
    val isAmoledTheme: Boolean,
    val appFontPreset: AppFontPreset,
    val expressiveAppFontPreset: AppFontPreset,
    val tabletUiMode: TabletUiMode,
    val appLocale: String,
    val loadingCircleStyle: AppSettings.LoadingCircleStyle,
    val popupRadius: Int,
    val homeHeroMode: HomeHeroMode,
    val homeHeroBackground: HomeHeroBackground,
    val homeHeroContentLayout: HomeHeroContentLayout,
    val listMode: ListMode,
    val gridSize: Int,
    val railAnimationIntensityPercent: Int,
    val isRailAnimationSettingsEnabled: Boolean,
    val isQuickFilterEnabled: Boolean,
    val progressIndicatorMode: ProgressIndicatorMode,
    val badgesTopLeft: Set<String>,
    val badgesTopRight: Set<String>,
    val badgesBottomLeft: Set<String>,
    val badgesBottomRight: Set<String>,
    val mangaListBadges: Set<String>, // Keep for compatibility if needed, but we will use the others
    val isDescriptionExpanded: Boolean,
    val isPanoramaCoverEnabled: Boolean,
    val panoramaCoverSummary: String,
    val isPagesTabEnabled: Boolean,
    val isDetailsTranslateButtonVisible: Boolean,
    val isModernDetailsDockEnabled: Boolean,
    val defaultDetailsTab: Int,
    val searchSuggestionTypes: Set<SearchSuggestionType>,
    val isSharedElementTransitionsEnabled: Boolean,
    val isSharedElementTransitionsSettingsEnabled: Boolean,
    val isShowLanguagePresetFilter: Boolean,
    val hiddenLanguagePreset: String,
    val isShowContentTypeFilter: Boolean,
    val hiddenContentType: String,
    val isShowSourceTagFilter: Boolean,
    val hiddenSourceTag: Set<String>,
    val isMainFabEnabled: Boolean,
    val isNavBarPinned: Boolean,
    val isNavLabelsVisible: Boolean,
    val isNavFloating: Boolean,
    val isNavLayeredSurface: Boolean,
    val isNavExpressivePillEnabled: Boolean,
    val navHeight: Int,
    val navFloatingHeight: Int,
    val isExitConfirmationEnabled: Boolean,
    val isDynamicShortcutsVisible: Boolean,
    val isDynamicShortcutsEnabled: Boolean,
    val isAppProtected: Boolean,
    val screenshotsPolicy: ScreenshotsPolicy,
)

data class AppearanceSettingsOptions(
    val colorSchemes: List<SettingsChoiceOption<ColorScheme>>,
    val interfaceStyles: List<SettingsChoiceOption<InterfaceStyle>>,
    val themes: List<SettingsChoiceOption<Int>>,
    val backgroundStyles: List<SettingsChoiceOption<BackgroundStyle>>,
    val fontPresets: List<SettingsChoiceOption<AppFontPreset>>,
    val tabletUiModes: List<SettingsChoiceOption<TabletUiMode>>,
    val appLocales: List<SettingsChoiceOption<String>>,
    val loadingCircleStyles: List<SettingsChoiceOption<AppSettings.LoadingCircleStyle>>,
    val popupRadii: List<SettingsChoiceOption<Int>>,
    val homeHeroModes: List<SettingsChoiceOption<HomeHeroMode>>,
    val homeHeroBackgrounds: List<SettingsChoiceOption<HomeHeroBackground>>,
    val homeHeroContentLayouts: List<SettingsChoiceOption<HomeHeroContentLayout>>,
    val listModes: List<SettingsChoiceOption<ListMode>>,
    val progressIndicatorModes: List<SettingsChoiceOption<ProgressIndicatorMode>>,
    val badgeOptions: List<SettingsChoiceOption<String>>,
    val bottomRightBadgeOptions: List<SettingsChoiceOption<String>>,
    val mangaListBadges: List<SettingsChoiceOption<String>>,
    val detailsTabs: List<SettingsChoiceOption<Int>>,
    val searchSuggestionTypes: List<SettingsChoiceOption<SearchSuggestionType>>,
    val languagePresets: List<SettingsChoiceOption<String>>,
    val contentTypes: List<SettingsChoiceOption<String>>,
    val sourceTags: List<SettingsChoiceOption<String>>,
    val screenshotsPolicies: List<SettingsChoiceOption<ScreenshotsPolicy>>,
)

enum class AppearanceSettingsPage {
    OVERVIEW,
    BADGES,
    SEARCH_FILTERS,
    NAVIGATION,
}

@Composable
fun AppearanceSettingsScreen(
    page: AppearanceSettingsPage = AppearanceSettingsPage.OVERVIEW,
    state: AppearanceSettingsUiState,
    options: AppearanceSettingsOptions,
    emptySelectionText: String,
    onInterfaceStyleChange: (InterfaceStyle) -> Unit,
    onColorSchemeChange: (ColorScheme) -> Unit,
    onThemeChange: (Int) -> Unit,
    onBackgroundStyleChange: (BackgroundStyle) -> Unit,
    onAmoledThemeChange: (Boolean) -> Unit,
    onAppFontPresetChange: (AppFontPreset) -> Unit,
    onExpressiveAppFontPresetChange: (AppFontPreset) -> Unit,
    onTabletUiModeChange: (TabletUiMode) -> Unit,
    onAppLocaleChange: (String) -> Unit,
    onLoadingCircleStyleChange: (AppSettings.LoadingCircleStyle) -> Unit,
    onPopupRadiusChange: (Int) -> Unit,
    onHomeHeroModeChange: (HomeHeroMode) -> Unit,
    onHomeHeroBackgroundChange: (HomeHeroBackground) -> Unit,
    onHomeHeroContentLayoutChange: (HomeHeroContentLayout) -> Unit,
    onListModeChange: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onRailAnimationIntensityChange: (Int) -> Unit,
    onQuickFilterChange: (Boolean) -> Unit,
    onProgressIndicatorModeChange: (ProgressIndicatorMode) -> Unit,
    onBadgesTopLeftChange: (Set<String>) -> Unit,
    onBadgesTopRightChange: (Set<String>) -> Unit,
    onBadgesBottomLeftChange: (Set<String>) -> Unit,
    onBadgesBottomRightChange: (Set<String>) -> Unit,
    onMangaListBadgesChange: (Set<String>) -> Unit,
    onDescriptionExpandedChange: (Boolean) -> Unit,
    onPanoramaCoverEnabledChange: (Boolean) -> Unit,
    onPanoramaSettingsClick: () -> Unit,
    onPagesTabEnabledChange: (Boolean) -> Unit,
    onDetailsTranslateButtonVisibleChange: (Boolean) -> Unit,
    onModernDetailsDockEnabledChange: (Boolean) -> Unit,
    onDefaultDetailsTabChange: (Int) -> Unit,
    onSearchSuggestionTypesChange: (Set<SearchSuggestionType>) -> Unit,
    onNavConfigClick: () -> Unit,
    onSharedElementTransitionsChange: (Boolean) -> Unit,
    onShowLanguagePresetFilterChange: (Boolean) -> Unit,
    onHiddenLanguagePresetChange: (String) -> Unit,
    onShowContentTypeFilterChange: (Boolean) -> Unit,
    onHiddenContentTypeChange: (String) -> Unit,
    onShowSourceTagFilterChange: (Boolean) -> Unit,
    onHiddenSourceTagChange: (Set<String>) -> Unit,
    onMainFabChange: (Boolean) -> Unit,
    onNavPinnedChange: (Boolean) -> Unit,
    onNavLabelsVisibleChange: (Boolean) -> Unit,
    onNavFloatingChange: (Boolean) -> Unit,
    onNavLayeredSurfaceChange: (Boolean) -> Unit,
    onNavExpressivePillChange: (Boolean) -> Unit,
    onNavHeightChange: (Int) -> Unit,
    onNavFloatingHeightChange: (Int) -> Unit,
    onExitConfirmationChange: (Boolean) -> Unit,
    onDynamicShortcutsChange: (Boolean) -> Unit,
    onAppProtectionChange: (Boolean) -> Unit,
    onScreenshotsPolicyChange: (ScreenshotsPolicy) -> Unit,
    onBadgesSettingsClick: () -> Unit = {},
    onSearchFiltersSettingsClick: () -> Unit = {},
    onNavigationSettingsClick: () -> Unit = {},
) {
    when (page) {
        AppearanceSettingsPage.BADGES -> {
            AppearanceBadgesSettingsScreen(
                state = state,
                options = options,
                emptySelectionText = emptySelectionText,
                onBadgesTopLeftChange = onBadgesTopLeftChange,
                onBadgesTopRightChange = onBadgesTopRightChange,
                onBadgesBottomLeftChange = onBadgesBottomLeftChange,
                onBadgesBottomRightChange = onBadgesBottomRightChange,
            )
            return
        }
        AppearanceSettingsPage.SEARCH_FILTERS -> {
            AppearanceSearchFiltersSettingsScreen(
                state = state,
                options = options,
                emptySelectionText = emptySelectionText,
                onShowLanguagePresetFilterChange = onShowLanguagePresetFilterChange,
                onHiddenLanguagePresetChange = onHiddenLanguagePresetChange,
                onShowContentTypeFilterChange = onShowContentTypeFilterChange,
                onHiddenContentTypeChange = onHiddenContentTypeChange,
                onShowSourceTagFilterChange = onShowSourceTagFilterChange,
                onHiddenSourceTagChange = onHiddenSourceTagChange,
            )
            return
        }
        AppearanceSettingsPage.NAVIGATION -> {
            AppearanceNavigationSettingsScreen(
                state = state,
                onNavPinnedChange = onNavPinnedChange,
                onNavLabelsVisibleChange = onNavLabelsVisibleChange,
                onNavFloatingChange = onNavFloatingChange,
                onNavLayeredSurfaceChange = onNavLayeredSurfaceChange,
                onNavExpressivePillChange = onNavExpressivePillChange,
                onNavHeightChange = onNavHeightChange,
                onNavFloatingHeightChange = onNavFloatingHeightChange,
            )
            return
        }
        AppearanceSettingsPage.OVERVIEW -> Unit
    }
    val usesExpressiveTypography = true
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(8.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item(key = "appearance_theme_and_color") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_theme_and_color),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.interface_style),
                        iconRes = R.drawable.ic_appearance,
                        value = state.interfaceStyle,
                        options = options.interfaceStyles,
                        onValueChange = onInterfaceStyleChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.color_theme),
                        iconRes = R.drawable.ic_auto_fix,
                        value = state.colorScheme,
                        options = options.colorSchemes,
                        styleHint = if (state.interfaceStyle == InterfaceStyle.IOS) {
                            stringResource(R.string.appearance_color_scheme_ios_note)
                        } else {
                            null
                        },
                        onValueChange = onColorSchemeChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.appearance_mode),
                        iconRes = R.drawable.ic_timelapse,
                        value = state.theme,
                        options = options.themes,
                        onValueChange = onThemeChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.background_style),
                        iconRes = R.drawable.ic_images,
                        value = state.backgroundStyle,
                        options = options.backgroundStyles,
                        summary = stringResource(R.string.background_style_summary),
                        onValueChange = onBackgroundStyleChange,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.black_dark_theme),
                        iconRes = R.drawable.ic_eye_off,
                        checked = state.isAmoledTheme,
                        summary = stringResource(R.string.black_dark_theme_summary),
                        onCheckedChange = onAmoledThemeChange,
                    )
                }
            }
        }

        item(key = "appearance_text_and_language") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_text_and_language),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.pref_app_font_preset),
                        iconRes = R.drawable.ic_read,
                        value = if (usesExpressiveTypography) {
                            state.expressiveAppFontPreset
                        } else {
                            state.appFontPreset
                        },
                        options = options.fontPresets,
                        summary = stringResource(R.string.pref_app_font_preset_summary),
                        styleHint = if (state.interfaceStyle == InterfaceStyle.IOS) {
                            stringResource(
                                R.string.appearance_ios_font_note,
                                options.fontPresets.firstOrNull {
                                    it.value == state.expressiveAppFontPreset
                                }?.label.orEmpty(),
                            )
                        } else {
                            null
                        },
                        onValueChange = if (usesExpressiveTypography) {
                            onExpressiveAppFontPresetChange
                        } else {
                            onAppFontPresetChange
                        },
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.language),
                        iconRes = R.drawable.ic_language,
                        value = state.appLocale,
                        options = options.appLocales,
                        onValueChange = onAppLocaleChange,
                    )
                }
            }
        }

        item(key = "appearance_interface_components") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_interface_components),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.tablet_ui_mode),
                        iconRes = R.drawable.ic_aspect_ratio,
                        value = state.tabletUiMode,
                        options = options.tabletUiModes,
                        onValueChange = onTabletUiModeChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.pref_loading_circle_style),
                        iconRes = R.drawable.ic_timer_run,
                        value = state.loadingCircleStyle,
                        options = options.loadingCircleStyles,
                        summary = stringResource(R.string.pref_loading_circle_style_summary),
                        onValueChange = onLoadingCircleStyleChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.pref_popup_radius),
                        iconRes = R.drawable.ic_aspect_ratio,
                        value = state.popupRadius,
                        options = options.popupRadii,
                        styleHint = stringResource(
                            if (state.popupRadius == -1) {
                                R.string.appearance_style_default_value
                            } else {
                                R.string.appearance_style_custom_override
                            },
                            stringResource(state.interfaceStyle.titleResId),
                            "${state.interfaceStyle.tokens().groupCornerRadius.value.toInt()}dp",
                        ),
                        onValueChange = onPopupRadiusChange,
                    )
                }
            }
        }

        item(key = "manga_list_layout") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_list_layout),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.list_mode),
                        iconRes = R.drawable.ic_list,
                        value = state.listMode,
                        options = options.listModes,
                        onValueChange = onListModeChange,
                    )
                }
                item {
                    SettingsSliderPreference(
                        title = stringResource(R.string.grid_size),
                        iconRes = R.drawable.ic_grid,
                        value = state.gridSize,
                        valueRange = 50..150,
                        step = 5,
                        valueText = { "$it%" },
                        onValueChange = onGridSizeChange,
                    )
                }
            }
        }

        item(key = "manga_list_interaction") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_list_interaction),
            ) {
                item {
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_rail_animation_intensity),
                        iconRes = R.drawable.ic_move_horizontal,
                        value = state.railAnimationIntensityPercent,
                        valueRange = 0..300,
                        step = 10,
                        summary = stringResource(R.string.pref_rail_animation_intensity_summary),
                        valueText = { "$it%" },
                        enabled = state.isRailAnimationSettingsEnabled,
                        onValueChange = onRailAnimationIntensityChange,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.show_quick_filters),
                        iconRes = R.drawable.ic_filter_menu,
                        checked = state.isQuickFilterEnabled,
                        summary = stringResource(R.string.show_quick_filters_summary),
                        onCheckedChange = onQuickFilterChange,
                    )
                }
            }
        }

        item(key = "manga_list_information") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_list_information),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.show_reading_indicators),
                        iconRes = R.drawable.ic_progress_marker,
                        value = state.progressIndicatorMode,
                        options = options.progressIndicatorModes,
                        onValueChange = onProgressIndicatorModeChange,
                    )
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.badges_in_lists),
                        summary = stringResource(R.string.appearance_badges_group_summary),
                        iconRes = R.drawable.ic_bookmark_selector,
                        onClick = onBadgesSettingsClick,
                    )
                }
            }
        }

        item(key = "details_content") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_details_content),
            ) {
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.collapse_long_description),
                        iconRes = R.drawable.ic_expand_more,
                        checked = !state.isDescriptionExpanded,
                        onCheckedChange = { onDescriptionExpandedChange(!it) },
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.show_pages_thumbs),
                        iconRes = R.drawable.ic_book_page,
                        checked = state.isPagesTabEnabled,
                        summary = stringResource(R.string.show_pages_thumbs_summary),
                        onCheckedChange = onPagesTabEnabledChange,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.details_translate_button_visible),
                        iconRes = R.drawable.ic_translate,
                        checked = state.isDetailsTranslateButtonVisible,
                        summary = stringResource(R.string.details_translate_button_visible_summary),
                        onCheckedChange = onDetailsTranslateButtonVisibleChange,
                    )
                }
                if (state.isPagesTabEnabled) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.default_tab),
                            iconRes = R.drawable.ic_list_detailed,
                            value = state.defaultDetailsTab,
                            options = options.detailsTabs,
                            onValueChange = onDefaultDetailsTabChange,
                        )
                    }
                }
            }
        }

        item(key = "details_visual") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_details_visual),
            ) {
                item {
                    SettingsSplitSwitchPreference(
                        title = stringResource(R.string.pref_panorama_cover),
                        iconRes = R.drawable.ic_images,
                        checked = state.isPanoramaCoverEnabled,
                        summary = state.panoramaCoverSummary,
                        onClick = onPanoramaSettingsClick,
                        onCheckedChange = onPanoramaCoverEnabledChange,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.modern_details_dock),
                        iconRes = R.drawable.ic_drawer_menu,
                        checked = state.isModernDetailsDockEnabled,
                        summary = stringResource(R.string.modern_details_dock_summary),
                        onCheckedChange = onModernDetailsDockEnabledChange,
                    )
                }
            }
        }

        item(key = "main_home_display_${state.homeHeroMode}") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_home_display),
            ) {
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.pref_home_hero_mode),
                        iconRes = R.drawable.ic_home_filled,
                        value = state.homeHeroMode,
                        options = options.homeHeroModes,
                        summary = stringResource(R.string.pref_home_hero_mode_summary),
                        onValueChange = onHomeHeroModeChange,
                    )
                }
                if (state.homeHeroMode == HomeHeroMode.FIXED) {
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.pref_home_hero_background),
                            iconRes = R.drawable.ic_images,
                            value = state.homeHeroBackground,
                            options = options.homeHeroBackgrounds,
                            onValueChange = onHomeHeroBackgroundChange,
                        )
                    }
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.pref_home_hero_content_layout),
                            iconRes = R.drawable.ic_list_detailed,
                            value = state.homeHeroContentLayout,
                            options = options.homeHeroContentLayouts,
                            onValueChange = onHomeHeroContentLayoutChange,
                        )
                    }
                }
            }
        }

        item(key = "main_content") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_group_main_content),
            ) {
                item {
                    SettingsMultiChoicePreference(
                        title = stringResource(R.string.search_suggestions),
                        iconRes = R.drawable.ic_suggestion,
                        values = state.searchSuggestionTypes,
                        options = options.searchSuggestionTypes,
                        emptySelectionText = emptySelectionText,
                        onValueChange = onSearchSuggestionTypesChange,
                    )
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.main_screen_sections),
                        iconRes = R.drawable.ic_home,
                        summary = state.navSummary,
                        onClick = onNavConfigClick,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.main_screen_fab),
                        iconRes = R.drawable.ic_shortcut,
                        checked = state.isMainFabEnabled,
                        summary = stringResource(R.string.main_screen_fab_summary),
                        onCheckedChange = onMainFabChange,
                    )
                }
                if (state.isDynamicShortcutsVisible) {
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.history_shortcuts),
                            iconRes = R.drawable.ic_history,
                            checked = state.isDynamicShortcutsEnabled,
                            summary = stringResource(R.string.history_shortcuts_summary),
                            onCheckedChange = onDynamicShortcutsChange,
                        )
                    }
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.search_bar_filters),
                        summary = stringResource(R.string.appearance_search_filters_group_summary),
                        iconRes = R.drawable.ic_filter_menu,
                        onClick = onSearchFiltersSettingsClick,
                    )
                }
                item {
                    SettingsActionPreference(
                        title = stringResource(R.string.appearance_navigation_group),
                        summary = stringResource(R.string.appearance_navigation_group_summary),
                        iconRes = R.drawable.ic_drawer_menu,
                        onClick = onNavigationSettingsClick,
                    )
                }
            }
        }

        item(key = "interaction_behavior") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.appearance_behavior_section),
            ) {
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.shared_element_transitions),
                        iconRes = R.drawable.ic_move_horizontal,
                        checked = state.isSharedElementTransitionsEnabled,
                        summary = stringResource(R.string.shared_element_transitions_summary),
                        enabled = state.isSharedElementTransitionsSettingsEnabled,
                        onCheckedChange = onSharedElementTransitionsChange,
                    )
                }
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.exit_confirmation),
                        iconRes = R.drawable.ic_alert_outline,
                        checked = state.isExitConfirmationEnabled,
                        summary = stringResource(R.string.exit_confirmation_summary),
                        onCheckedChange = onExitConfirmationChange,
                    )
                }
            }
        }

        item(key = "privacy") {
            SettingsPreferenceGroup(
                title = stringResource(R.string.privacy),
            ) {
                item {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.protect_application),
                        iconRes = R.drawable.ic_lock,
                        checked = state.isAppProtected,
                        summary = stringResource(R.string.protect_application_summary),
                        onCheckedChange = onAppProtectionChange,
                    )
                }
                item {
                    SettingsChoicePreference(
                        title = stringResource(R.string.screenshots_policy),
                        iconRes = R.drawable.ic_eye,
                        value = state.screenshotsPolicy,
                        options = options.screenshotsPolicies,
                        onValueChange = onScreenshotsPolicyChange,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AppearanceSubpage(
    content: SettingsItemGroupScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(8.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                SettingsPreferenceGroup(title = "", content = content)
            }
        }
    }
}

@Composable
private fun AppearanceBadgesSettingsScreen(
    state: AppearanceSettingsUiState,
    options: AppearanceSettingsOptions,
    emptySelectionText: String,
    onBadgesTopLeftChange: (Set<String>) -> Unit,
    onBadgesTopRightChange: (Set<String>) -> Unit,
    onBadgesBottomLeftChange: (Set<String>) -> Unit,
    onBadgesBottomRightChange: (Set<String>) -> Unit,
) = AppearanceSubpage {
    item {
        SettingsMultiChoicePreference(
            title = stringResource(R.string.badge_top_left),
            iconRes = R.drawable.ic_bookmark,
            values = state.badgesTopLeft,
            options = options.badgeOptions,
            emptySelectionText = emptySelectionText,
            onValueChange = onBadgesTopLeftChange,
        )
    }
    item {
        SettingsMultiChoicePreference(
            title = stringResource(R.string.badge_top_right),
            iconRes = R.drawable.ic_star_small,
            values = state.badgesTopRight,
            options = options.badgeOptions,
            emptySelectionText = emptySelectionText,
            onValueChange = onBadgesTopRightChange,
        )
    }
    item {
        SettingsMultiChoicePreference(
            title = stringResource(R.string.badge_bottom_left),
            iconRes = R.drawable.ic_new,
            values = state.badgesBottomLeft,
            options = options.badgeOptions,
            emptySelectionText = emptySelectionText,
            onValueChange = onBadgesBottomLeftChange,
        )
    }
    item {
        SettingsMultiChoicePreference(
            title = stringResource(R.string.badge_bottom_right),
            iconRes = R.drawable.ic_progress_marker,
            values = state.badgesBottomRight,
            options = options.bottomRightBadgeOptions,
            emptySelectionText = emptySelectionText,
            onValueChange = onBadgesBottomRightChange,
        )
    }
}

@Composable
private fun AppearanceSearchFiltersSettingsScreen(
    state: AppearanceSettingsUiState,
    options: AppearanceSettingsOptions,
    emptySelectionText: String,
    onShowLanguagePresetFilterChange: (Boolean) -> Unit,
    onHiddenLanguagePresetChange: (String) -> Unit,
    onShowContentTypeFilterChange: (Boolean) -> Unit,
    onHiddenContentTypeChange: (String) -> Unit,
    onShowSourceTagFilterChange: (Boolean) -> Unit,
    onHiddenSourceTagChange: (Set<String>) -> Unit,
) = AppearanceSubpage {
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.show_language_preset_filter),
            iconRes = R.drawable.ic_language,
            checked = state.isShowLanguagePresetFilter,
            onCheckedChange = onShowLanguagePresetFilterChange,
        )
    }
        if (!state.isShowLanguagePresetFilter) {
            item {
                SettingsChoicePreference(
                    title = stringResource(R.string.fixed_language_preset),
                    iconRes = R.drawable.ic_language,
                    value = state.hiddenLanguagePreset,
                    options = options.languagePresets,
                    onValueChange = onHiddenLanguagePresetChange,
                )
            }
        }
        item {
        SettingsSwitchPreference(
            title = stringResource(R.string.show_content_type_filter),
            iconRes = R.drawable.ic_filter_content_type,
            checked = state.isShowContentTypeFilter,
            onCheckedChange = onShowContentTypeFilterChange,
        )
        }
        if (!state.isShowContentTypeFilter) {
            item {
                SettingsChoicePreference(
                    title = stringResource(R.string.fixed_content_type),
                    iconRes = R.drawable.ic_filter_content_type,
                    value = state.hiddenContentType,
                    options = options.contentTypes,
                    onValueChange = onHiddenContentTypeChange,
                )
            }
        }
        item {
        SettingsSwitchPreference(
            title = stringResource(R.string.show_source_tag_filter),
            iconRes = R.drawable.ic_tag,
            checked = state.isShowSourceTagFilter,
            onCheckedChange = onShowSourceTagFilterChange,
        )
        }
        if (!state.isShowSourceTagFilter) {
            item {
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.fixed_source_tag),
                    iconRes = R.drawable.ic_tag,
                    values = state.hiddenSourceTag,
                    options = options.sourceTags,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onHiddenSourceTagChange,
                )
            }
        }
}

@Composable
private fun AppearanceNavigationSettingsScreen(
    state: AppearanceSettingsUiState,
    onNavPinnedChange: (Boolean) -> Unit,
    onNavLabelsVisibleChange: (Boolean) -> Unit,
    onNavFloatingChange: (Boolean) -> Unit,
    onNavLayeredSurfaceChange: (Boolean) -> Unit,
    onNavExpressivePillChange: (Boolean) -> Unit,
    onNavHeightChange: (Int) -> Unit,
    onNavFloatingHeightChange: (Int) -> Unit,
) = AppearanceSubpage {
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.pin_navigation_ui),
            iconRes = R.drawable.ic_pin,
            checked = state.isNavBarPinned,
            summary = stringResource(R.string.pin_navigation_ui_summary),
            onCheckedChange = onNavPinnedChange,
        )
    }
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.show_labels_in_navbar),
            iconRes = R.drawable.ic_list_detailed,
            checked = state.isNavLabelsVisible,
            onCheckedChange = onNavLabelsVisibleChange,
        )
    }
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.pref_nav_floating),
            iconRes = R.drawable.ic_move_horizontal,
            checked = state.isNavFloating,
            summary = stringResource(R.string.pref_nav_floating_summary),
            onCheckedChange = onNavFloatingChange,
        )
    }
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.pref_nav_layered_surface),
            iconRes = R.drawable.ic_view_column,
            checked = state.isNavLayeredSurface,
            summary = stringResource(R.string.pref_nav_layered_surface_summary),
            onCheckedChange = onNavLayeredSurfaceChange,
        )
    }
    item {
        SettingsSwitchPreference(
            title = stringResource(R.string.pref_nav_expressive_pill),
            iconRes = R.drawable.ic_aspect_ratio,
            checked = state.isNavExpressivePillEnabled,
            summary = stringResource(R.string.pref_nav_expressive_pill_summary),
            styleHint = stringResource(
                R.string.appearance_style_default_value,
                stringResource(state.interfaceStyle.titleResId),
                stringResource(R.string.enabled),
            ),
            enabled = state.isNavFloating,
            onCheckedChange = onNavExpressivePillChange,
        )
    }
    item {
        SettingsSliderPreference(
            title = stringResource(R.string.pref_nav_height),
            iconRes = R.drawable.ic_size_large,
            value = state.navHeight,
            valueRange = 48..88,
            step = 4,
            valueText = { "${it}dp" },
            onValueChange = onNavHeightChange,
        )
    }
    item {
        SettingsSliderPreference(
            title = stringResource(R.string.pref_nav_floating_height),
            iconRes = R.drawable.ic_split_horizontal,
            value = state.navFloatingHeight,
            valueRange = 48..84,
            step = 4,
            valueText = { "${it}dp" },
            enabled = state.isNavFloating,
            onValueChange = onNavFloatingHeightChange,
        )
    }
}
