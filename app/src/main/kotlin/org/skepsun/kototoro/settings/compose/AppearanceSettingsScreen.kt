package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.skepsun.kototoro.core.ui.compose.PanoramaAnimationSpeedMaxPercent
import org.skepsun.kototoro.core.ui.compose.PanoramaAnimationSpeedMinPercent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.AppFontPreset
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.ColorScheme
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
    val panoramaCoverBlur: Int,
    val panoramaTransitionIntensity: Int,
    val isPanoramaCoverAnimationEnabled: Boolean,
    val isPanoramaCoverAnimationSettingsEnabled: Boolean,
    val panoramaAnimationSpeed: Int,
    val panoramaCoverExtraHeight: Int,
    val panoramaBottomGradientAlpha: Int,
    val browsePanoramaBlendHeight: Int,
    val browsePanoramaBottomGradientAlpha: Int,
    val isPanoramaDownsampleEnabled: Boolean,
    val isDetailsPanoramaScrollLinkedEnabled: Boolean,
    val isDetailsPanoramaLimitedToInfoCardMidpoint: Boolean,
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

@Composable
fun AppearanceSettingsScreen(
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
    onPanoramaBlurChange: (Int) -> Unit,
    onPanoramaTransitionIntensityChange: (Int) -> Unit,
    onPanoramaAnimationEnabledChange: (Boolean) -> Unit,
    onPanoramaAnimationSpeedChange: (Int) -> Unit,
    onPanoramaExtraHeightChange: (Int) -> Unit,
    onPanoramaGradientAlphaChange: (Int) -> Unit,
    onBrowsePanoramaBlendHeightChange: (Int) -> Unit,
    onBrowsePanoramaGradientAlphaChange: (Int) -> Unit,
    onPanoramaDownsampleEnabledChange: (Boolean) -> Unit,
    onDetailsPanoramaScrollLinkedChange: (Boolean) -> Unit,
    onDetailsPanoramaLimitedToInfoCardMidpointChange: (Boolean) -> Unit,
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
    onNavExpressivePillChange: (Boolean) -> Unit,
    onNavHeightChange: (Int) -> Unit,
    onNavFloatingHeightChange: (Int) -> Unit,
    onExitConfirmationChange: (Boolean) -> Unit,
    onDynamicShortcutsChange: (Boolean) -> Unit,
    onAppProtectionChange: (Boolean) -> Unit,
    onScreenshotsPolicyChange: (ScreenshotsPolicy) -> Unit,
) {
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
                top = 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item(key = "appearance") {
            SettingsPreferenceSection(title = stringResource(R.string.appearance)) {
                SettingsChoicePreference(
                    title = stringResource(R.string.interface_style),
                    value = state.interfaceStyle,
                    options = options.interfaceStyles,
                    onValueChange = onInterfaceStyleChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.color_theme),
                    value = state.colorScheme,
                    options = options.colorSchemes,
                    styleHint = if (state.interfaceStyle == InterfaceStyle.IOS) {
                        stringResource(R.string.appearance_color_scheme_ios_note)
                    } else {
                        null
                    },
                    onValueChange = onColorSchemeChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.appearance_mode),
                    value = state.theme,
                    options = options.themes,
                    onValueChange = onThemeChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.background_style),
                    value = state.backgroundStyle,
                    options = options.backgroundStyles,
                    summary = stringResource(R.string.background_style_summary),
                    styleHint = if (state.interfaceStyle == InterfaceStyle.IOS) {
                        stringResource(R.string.appearance_background_ios_note)
                    } else {
                        null
                    },
                    onValueChange = onBackgroundStyleChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.black_dark_theme),
                    checked = state.isAmoledTheme,
                    summary = stringResource(R.string.black_dark_theme_summary),
                    onCheckedChange = onAmoledThemeChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.pref_app_font_preset),
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
                            options.fontPresets.firstOrNull { it.value == state.expressiveAppFontPreset }?.label.orEmpty(),
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
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.tablet_ui_mode),
                    value = state.tabletUiMode,
                    options = options.tabletUiModes,
                    onValueChange = onTabletUiModeChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.language),
                    value = state.appLocale,
                    options = options.appLocales,
                    onValueChange = onAppLocaleChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.pref_loading_circle_style),
                    value = state.loadingCircleStyle,
                    options = options.loadingCircleStyles,
                    summary = stringResource(R.string.pref_loading_circle_style_summary),
                    onValueChange = onLoadingCircleStyleChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.pref_popup_radius),
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

        item(key = "manga_list") {
            SettingsPreferenceSection(title = stringResource(R.string.manga_list)) {
                SettingsChoicePreference(
                    title = stringResource(R.string.list_mode),
                    value = state.listMode,
                    options = options.listModes,
                    onValueChange = onListModeChange,
                )
                SettingsSectionDivider()
                SettingsSliderPreference(
                    title = stringResource(R.string.grid_size),
                    value = state.gridSize,
                    valueRange = 50..150,
                    step = 5,
                    valueText = { "$it%" },
                    onValueChange = onGridSizeChange,
                )
                SettingsSectionDivider()
                SettingsSliderPreference(
                    title = stringResource(R.string.pref_rail_animation_intensity),
                    value = state.railAnimationIntensityPercent,
                    valueRange = 0..300,
                    step = 10,
                    summary = stringResource(R.string.pref_rail_animation_intensity_summary),
                    valueText = { "$it%" },
                    enabled = state.isRailAnimationSettingsEnabled,
                    onValueChange = onRailAnimationIntensityChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.show_quick_filters),
                    checked = state.isQuickFilterEnabled,
                    summary = stringResource(R.string.show_quick_filters_summary),
                    onCheckedChange = onQuickFilterChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.show_reading_indicators),
                    value = state.progressIndicatorMode,
                    options = options.progressIndicatorModes,
                    onValueChange = onProgressIndicatorModeChange,
                )
                SettingsSectionDivider()
                SettingsGroupLabel(text = stringResource(R.string.badges_in_lists))
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.badge_top_left),
                    values = state.badgesTopLeft,
                    options = options.badgeOptions,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onBadgesTopLeftChange,
                )
                SettingsSectionDivider()
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.badge_top_right),
                    values = state.badgesTopRight,
                    options = options.badgeOptions,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onBadgesTopRightChange,
                )
                SettingsSectionDivider()
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.badge_bottom_left),
                    values = state.badgesBottomLeft,
                    options = options.badgeOptions,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onBadgesBottomLeftChange,
                )
                SettingsSectionDivider()
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.badge_bottom_right),
                    values = state.badgesBottomRight,
                    options = options.bottomRightBadgeOptions,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onBadgesBottomRightChange,
                )
            }
        }

        item(key = "details") {
            SettingsPreferenceSection(title = stringResource(R.string.details)) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.collapse_long_description),
                    checked = !state.isDescriptionExpanded,
                    onCheckedChange = { onDescriptionExpandedChange(!it) },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.pref_panorama_cover),
                    checked = state.isPanoramaCoverEnabled,
                    summary = stringResource(R.string.pref_panorama_cover_summary),
                    onCheckedChange = onPanoramaCoverEnabledChange,
                )
                if (state.isPanoramaCoverEnabled) {
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.pref_details_panorama_limit_to_info_card_midpoint),
                        checked = state.isDetailsPanoramaLimitedToInfoCardMidpoint,
                        summary = stringResource(R.string.pref_details_panorama_limit_to_info_card_midpoint_summary),
                        onCheckedChange = onDetailsPanoramaLimitedToInfoCardMidpointChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.pref_details_panorama_scroll_linked),
                        checked = state.isDetailsPanoramaScrollLinkedEnabled &&
                            state.isDetailsPanoramaLimitedToInfoCardMidpoint,
                        summary = stringResource(R.string.pref_details_panorama_scroll_linked_summary),
                        enabled = state.isDetailsPanoramaLimitedToInfoCardMidpoint,
                        onCheckedChange = onDetailsPanoramaScrollLinkedChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_panorama_transition_intensity),
                        value = state.panoramaTransitionIntensity,
                        valueRange = 0..100,
                        step = 5,
                        summary = stringResource(R.string.pref_panorama_transition_intensity_summary),
                        valueText = { "$it%" },
                        onValueChange = onPanoramaTransitionIntensityChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_panorama_blur),
                        value = state.panoramaCoverBlur,
                        valueRange = 0..100,
                        step = 5,
                        valueText = { "$it%" },
                        onValueChange = onPanoramaBlurChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.pref_panorama_animation),
                        checked = state.isPanoramaCoverAnimationEnabled,
                        summary = stringResource(R.string.pref_panorama_animation_summary),
                        enabled = state.isPanoramaCoverAnimationSettingsEnabled,
                        onCheckedChange = onPanoramaAnimationEnabledChange,
                    )
                    if (state.isPanoramaCoverAnimationEnabled) {
                        SettingsSectionDivider()
                        SettingsSliderPreference(
                            title = stringResource(R.string.pref_panorama_animation_speed),
                            value = state.panoramaAnimationSpeed,
                            valueRange = PanoramaAnimationSpeedMinPercent..PanoramaAnimationSpeedMaxPercent,
                            step = 25,
                            valueText = { "${it}%" },
                            onValueChange = onPanoramaAnimationSpeedChange,
                        )
                    }
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_panorama_extra_height),
                        value = state.panoramaCoverExtraHeight,
                        valueRange = 0..100,
                        step = 5,
                        valueText = { "${it}dp" },
                        onValueChange = onPanoramaExtraHeightChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_panorama_gradient_alpha),
                        value = state.panoramaBottomGradientAlpha,
                        valueRange = 0..100,
                        step = 5,
                        valueText = { "$it%" },
                        onValueChange = onPanoramaGradientAlphaChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_browse_panorama_blend_height),
                        value = state.browsePanoramaBlendHeight,
                        valueRange = 48..220,
                        step = 4,
                        valueText = { "${it}dp" },
                        onValueChange = onBrowsePanoramaBlendHeightChange,
                    )
                    SettingsSectionDivider()
                    SettingsSliderPreference(
                        title = stringResource(R.string.pref_browse_panorama_gradient_alpha),
                        value = state.browsePanoramaBottomGradientAlpha,
                        valueRange = 0..100,
                        step = 5,
                        valueText = { "$it%" },
                        onValueChange = onBrowsePanoramaGradientAlphaChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.pref_panorama_downsample),
                        checked = state.isPanoramaDownsampleEnabled,
                        summary = stringResource(R.string.pref_panorama_downsample_summary),
                        onCheckedChange = onPanoramaDownsampleEnabledChange,
                    )
                }
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.show_pages_thumbs),
                    checked = state.isPagesTabEnabled,
                    summary = stringResource(R.string.show_pages_thumbs_summary),
                    onCheckedChange = onPagesTabEnabledChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.details_translate_button_visible),
                    checked = state.isDetailsTranslateButtonVisible,
                    summary = stringResource(R.string.details_translate_button_visible_summary),
                    onCheckedChange = onDetailsTranslateButtonVisibleChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.modern_details_dock),
                    checked = state.isModernDetailsDockEnabled,
                    summary = stringResource(R.string.modern_details_dock_summary),
                    onCheckedChange = onModernDetailsDockEnabledChange,
                )
                if (state.isPagesTabEnabled) {
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.default_tab),
                        value = state.defaultDetailsTab,
                        options = options.detailsTabs,
                        onValueChange = onDefaultDetailsTabChange,
                    )
                }
            }
        }

        item(key = "main") {
            SettingsPreferenceSection(title = stringResource(R.string.main_screen)) {
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.search_suggestions),
                    values = state.searchSuggestionTypes,
                    options = options.searchSuggestionTypes,
                    emptySelectionText = emptySelectionText,
                    onValueChange = onSearchSuggestionTypesChange,
                )
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.main_screen_sections),
                    summary = state.navSummary,
                    onClick = onNavConfigClick,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.shared_element_transitions),
                    checked = state.isSharedElementTransitionsEnabled,
                    summary = stringResource(R.string.shared_element_transitions_summary),
                    enabled = state.isSharedElementTransitionsSettingsEnabled,
                    onCheckedChange = onSharedElementTransitionsChange,
                )
                SettingsSectionDivider()
                SearchBarFiltersBlock(
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
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.main_screen_fab),
                    checked = state.isMainFabEnabled,
                    summary = stringResource(R.string.main_screen_fab_summary),
                    onCheckedChange = onMainFabChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.pin_navigation_ui),
                    checked = state.isNavBarPinned,
                    summary = stringResource(R.string.pin_navigation_ui_summary),
                    onCheckedChange = onNavPinnedChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.show_labels_in_navbar),
                    checked = state.isNavLabelsVisible,
                    onCheckedChange = onNavLabelsVisibleChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.pref_nav_floating),
                    checked = state.isNavFloating,
                    summary = stringResource(R.string.pref_nav_floating_summary),
                    onCheckedChange = onNavFloatingChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.pref_nav_expressive_pill),
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
                SettingsSectionDivider()
                SettingsSliderPreference(
                    title = stringResource(R.string.pref_nav_height),
                    value = state.navHeight,
                    valueRange = 48..88,
                    step = 4,
                    valueText = { "${it}dp" },
                    onValueChange = onNavHeightChange,
                )
                SettingsSectionDivider()
                SettingsSliderPreference(
                    title = stringResource(R.string.pref_nav_floating_height),
                    value = state.navFloatingHeight,
                    valueRange = 48..84,
                    step = 4,
                    valueText = { "${it}dp" },
                    onValueChange = onNavFloatingHeightChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.exit_confirmation),
                    checked = state.isExitConfirmationEnabled,
                    summary = stringResource(R.string.exit_confirmation_summary),
                    onCheckedChange = onExitConfirmationChange,
                )
                if (state.isDynamicShortcutsVisible) {
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.history_shortcuts),
                        checked = state.isDynamicShortcutsEnabled,
                        summary = stringResource(R.string.history_shortcuts_summary),
                        onCheckedChange = onDynamicShortcutsChange,
                    )
                }
            }
        }

        item(key = "privacy") {
            SettingsPreferenceSection(title = stringResource(R.string.privacy)) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.protect_application),
                    checked = state.isAppProtected,
                    summary = stringResource(R.string.protect_application_summary),
                    onCheckedChange = onAppProtectionChange,
                )
                SettingsSectionDivider()
                SettingsChoicePreference(
                    title = stringResource(R.string.screenshots_policy),
                    value = state.screenshotsPolicy,
                    options = options.screenshotsPolicies,
                    onValueChange = onScreenshotsPolicyChange,
                )
            }
        }
        }
    }
}

@Composable
private fun SearchBarFiltersBlock(
    state: AppearanceSettingsUiState,
    options: AppearanceSettingsOptions,
    emptySelectionText: String,
    onShowLanguagePresetFilterChange: (Boolean) -> Unit,
    onHiddenLanguagePresetChange: (String) -> Unit,
    onShowContentTypeFilterChange: (Boolean) -> Unit,
    onHiddenContentTypeChange: (String) -> Unit,
    onShowSourceTagFilterChange: (Boolean) -> Unit,
    onHiddenSourceTagChange: (Set<String>) -> Unit,
) {
    SettingsGroupLabel(text = stringResource(R.string.search_bar_filters))
    SettingsSwitchPreference(
        title = stringResource(R.string.show_language_preset_filter),
        checked = state.isShowLanguagePresetFilter,
        onCheckedChange = onShowLanguagePresetFilterChange,
    )
    if (!state.isShowLanguagePresetFilter) {
        SettingsSectionDivider()
        SettingsChoicePreference(
            title = stringResource(R.string.fixed_language_preset),
            value = state.hiddenLanguagePreset,
            options = options.languagePresets,
            onValueChange = onHiddenLanguagePresetChange,
        )
    }
    SettingsSectionDivider()
    SettingsSwitchPreference(
        title = stringResource(R.string.show_content_type_filter),
        checked = state.isShowContentTypeFilter,
        onCheckedChange = onShowContentTypeFilterChange,
    )
    if (!state.isShowContentTypeFilter) {
        SettingsSectionDivider()
        SettingsChoicePreference(
            title = stringResource(R.string.fixed_content_type),
            value = state.hiddenContentType,
            options = options.contentTypes,
            onValueChange = onHiddenContentTypeChange,
        )
    }
    SettingsSectionDivider()
    SettingsSwitchPreference(
        title = stringResource(R.string.show_source_tag_filter),
        checked = state.isShowSourceTagFilter,
        onCheckedChange = onShowSourceTagFilterChange,
    )
    if (!state.isShowSourceTagFilter) {
        SettingsSectionDivider()
        SettingsMultiChoicePreference(
            title = stringResource(R.string.fixed_source_tag),
            values = state.hiddenSourceTag,
            options = options.sourceTags,
            emptySelectionText = emptySelectionText,
            onValueChange = onHiddenSourceTagChange,
        )
    }
}
