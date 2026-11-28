package org.skepsun.kototoro.settings.compose

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvTabRing
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.EInkRefreshColor
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.ReaderInfoBarLayout
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.settings.NovelRuleSettingsRows
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.core.ui.settings.ReaderCropSetting
import org.skepsun.kototoro.core.ui.settings.ReaderWebtoonSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderPreloadSettingsRow
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsIcons
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsUiState
import org.skepsun.kototoro.core.ui.settings.ReaderZoomSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderZoomSettingsIcons
import org.skepsun.kototoro.core.ui.settings.ReaderZoomSettingsUiState
import org.skepsun.kototoro.core.ui.settings.ReaderModeSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderModeSettingsIcons
import org.skepsun.kototoro.core.ui.settings.ReaderModeSettingsUiState
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.ui.settings.LocalSettingsSearchTarget

private const val READER_SETTINGS_COMMON_PAGE = 0
private const val READER_SETTINGS_MANGA_PAGE = 1

/** 小说阅读器已从本构建移除，该页不再进入分页器，仅保留分支定义以免大面积改动排版代码。 */
private const val READER_SETTINGS_NOVEL_PAGE = 2
private const val READER_SETTINGS_PAGE_COUNT = 2

internal data class ReaderInfoBarSettingsState(
    val enabled: Boolean,
    val layoutEnabled: Boolean,
    val cutoutAvoidanceEnabled: Boolean,
)

internal fun resolveReaderInfoBarSettingsState(infoBarEnabled: Boolean): ReaderInfoBarSettingsState {
    return ReaderInfoBarSettingsState(
        enabled = infoBarEnabled,
        layoutEnabled = infoBarEnabled,
        cutoutAvoidanceEnabled = infoBarEnabled,
    )
}

@Composable
fun ReaderSettingsScreen(
    settings: AppSettings,
    onReaderTapActionsClick: () -> Unit,
    onReplaceRulesClick: () -> Unit,
    onDictionaryRulesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs
    val context = LocalContext.current
    val resources = context.resources

    val readerModeNames = ReaderMode.entries.map { it.name }
    val zoomModeNames = ZoomMode.entries.map { it.name }
    val readerCropNames = listOf("1", "2")
    val readerOrientationNames = listOf(
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.toString(),
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR.toString(),
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT.toString(),
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE.toString()
    )
    val readerBackgroundNames = ReaderBackground.entries.map { it.name }
    val readerAnimationNames = ReaderAnimation.entries.map { it.name }
    val readerInfoBarLayoutOptions = listOf(
        SettingsChoiceOption(ReaderInfoBarLayout.CENTERED, stringResource(R.string.reader_info_bar_layout_centered)),
        SettingsChoiceOption(ReaderInfoBarLayout.SPLIT, stringResource(R.string.reader_info_bar_layout_split)),
        SettingsChoiceOption(
            ReaderInfoBarLayout.SPLIT_LEADING_PROGRESS,
            stringResource(R.string.reader_info_bar_layout_split_leading_progress),
        ),
    )
    val pagesPreloadNames = listOf("1", "2", "0")
    val eInkModeEnabled = settings.observeAsState(AppSettings.KEY_EINK_MODE) { isEInkModeEnabled }.value
    val eInkRefreshEnabled = settings.observeAsState(AppSettings.KEY_EINK_REFRESH) { isEInkRefreshEnabled }.value
    val chapterTitleAtBottom = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM) {
        isReaderChapterTitleAtBottom
    }.value
    val readerControlOptions = listOf(
        SettingsChoiceOption(ReaderControl.SCREEN_ROTATION, stringResource(R.string.screen_orientation)),
        SettingsChoiceOption(ReaderControl.SAVE_PAGE, stringResource(R.string.save_page)),
        SettingsChoiceOption(ReaderControl.TIMER, stringResource(R.string.automatic_scroll)),
        SettingsChoiceOption(ReaderControl.BOOKMARK, stringResource(R.string.bookmark_add)),
        SettingsChoiceOption(ReaderControl.TRANSLATE, stringResource(R.string.novel_translate)),
        SettingsChoiceOption(ReaderControl.DOWNLOAD, stringResource(R.string.download)),
    )
    val novelReaderControlOptions = listOf(
        SettingsChoiceOption(ReaderControl.TIMER, stringResource(R.string.automatic_scroll)),
        SettingsChoiceOption(ReaderControl.BOOKMARK, stringResource(R.string.bookmark_add)),
        SettingsChoiceOption(ReaderControl.TRANSLATE, stringResource(R.string.novel_translate)),
        SettingsChoiceOption(ReaderControl.TTS, stringResource(R.string.novel_read_aloud)),
    )
    val pagerState = rememberPagerState(
        initialPage = READER_SETTINGS_COMMON_PAGE,
        pageCount = { READER_SETTINGS_PAGE_COUNT },
    )
    val coroutineScope = rememberCoroutineScope()
    val searchTarget = LocalSettingsSearchTarget.current
    LaunchedEffect(searchTarget) {
        searchTarget?.let { target -> pagerState.scrollToPage(readerSettingsPageForKey(target.key)) }
    }
    val pageLabels = listOf(
        stringResource(R.string.reader_settings_common_tab),
        stringResource(R.string.manga),
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = settingsContentTopInset()),
        ) {
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                pageLabels.forEachIndexed { index, label ->
                    Tab(
                        modifier = Modifier.tvTabRing(),
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(label) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.reader_settings_scope_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
                ) {
                    when (page) {
                        READER_SETTINGS_MANGA_PAGE -> ReaderMangaSettingsPage(
                            settings = settings,
                            prefs = prefs,
                            readerModeNames = readerModeNames,
                            zoomModeNames = zoomModeNames,
                            readerCropNames = readerCropNames,
                            readerOrientationNames = readerOrientationNames,
                            readerBackgroundNames = readerBackgroundNames,
                            readerAnimationNames = readerAnimationNames,
                            readerInfoBarLayoutOptions = readerInfoBarLayoutOptions,
                            pagesPreloadNames = pagesPreloadNames,
                            readerControlOptions = readerControlOptions,
                        )

                        
                        else -> {
                            SettingsPreferenceGroup(
                                title = stringResource(R.string.reader_settings_common_tab),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                item {
                                    SettingsActionPreference(
                                        title = stringResource(R.string.reader_actions),
                                        summary = stringResource(R.string.reader_actions_summary),
                                        iconRes = R.drawable.ic_tap,
                                        onClick = onReaderTapActionsClick,
                                    )
                                }
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.switch_pages_volume_buttons),
                                        summary = stringResource(R.string.switch_pages_volume_buttons_summary),
                                        iconRes = R.drawable.ic_volume_up,
                                        checked = settings.observeAsState(AppSettings.KEY_READER_VOLUME_BUTTONS) {
                                            prefs.getBoolean(AppSettings.KEY_READER_VOLUME_BUTTONS, false)
                                        }.value,
                                        onCheckedChange = {
                                            prefs.edit { putBoolean(AppSettings.KEY_READER_VOLUME_BUTTONS, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.reader_navigation_inverted),
                                        summary = stringResource(R.string.reader_navigation_inverted_summary),
                                        iconRes = R.drawable.ic_swap_vert,
                                        checked = settings.observeAsState(
                                            AppSettings.KEY_READER_NAVIGATION_INVERTED,
                                        ) {
                                            prefs.getBoolean(AppSettings.KEY_READER_NAVIGATION_INVERTED, false)
                                        }.value,
                                        onCheckedChange = {
                                            prefs.edit { putBoolean(AppSettings.KEY_READER_NAVIGATION_INVERTED, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.reader_control_labels),
                                        summary = stringResource(R.string.reader_control_labels_summary),
                                        iconRes = R.drawable.ic_list_detailed,
                                        checked = settings.observeAsState(AppSettings.KEY_READER_CONTROL_LABELS) {
                                            isReaderControlLabelsEnabled
                                        }.value,
                                        onCheckedChange = {
                                            prefs.edit { putBoolean(AppSettings.KEY_READER_CONTROL_LABELS, it) }
                                        },
                                    )
                                }
                            }

                            SettingsPreferenceGroup(
                                title = stringResource(R.string.reader_settings_group_reading_progress),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp),
                            ) {
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.auto_mark_completed),
                                        summary = stringResource(R.string.auto_mark_completed_summary),
                                        iconRes = R.drawable.ic_state_finished,
                                        checked = settings.observeAsState(AppSettings.KEY_AUTO_MARK_COMPLETED) {
                                            settings.isAutoMarkCompletedEnabled
                                        }.value,
                                        onCheckedChange = { settings.isAutoMarkCompletedEnabled = it },
                                    )
                                }
                            }

                            SettingsPreferenceGroup(
                                title = stringResource(R.string.e_ink),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp),
                            ) {
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.eink_mode),
                                        summary = stringResource(R.string.eink_mode_summary),
                                        iconRes = R.drawable.ic_auto_fix,
                                        checked = eInkModeEnabled,
                                        onCheckedChange = {
                                            prefs.edit { putBoolean(AppSettings.KEY_EINK_MODE, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.eink_refresh),
                                        summary = stringResource(R.string.eink_refresh_summary),
                                        iconRes = R.drawable.ic_screen_rotation_lock,
                                        checked = eInkRefreshEnabled,
                                        enabled = eInkModeEnabled,
                                        onCheckedChange = {
                                            prefs.edit { putBoolean(AppSettings.KEY_EINK_REFRESH, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.eink_refresh_duration),
                                        iconRes = R.drawable.ic_timer,
                                        value = settings.observeAsState(AppSettings.KEY_EINK_REFRESH_DURATION) {
                                            eInkRefreshDurationMillis
                                        }.value,
                                        valueRange = AppSettings.EINK_REFRESH_DURATION_MIN..
                                            AppSettings.EINK_REFRESH_DURATION_MAX,
                                        step = 50,
                                        enabled = eInkModeEnabled && eInkRefreshEnabled,
                                        valueText = { resources.getString(R.string.milliseconds_value, it) },
                                        onValueChange = {
                                            prefs.edit { putInt(AppSettings.KEY_EINK_REFRESH_DURATION, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.eink_refresh_every),
                                        iconRes = R.drawable.ic_timeline,
                                        value = settings.observeAsState(AppSettings.KEY_EINK_REFRESH_EVERY) {
                                            eInkRefreshEveryPages
                                        }.value,
                                        valueRange = AppSettings.EINK_REFRESH_EVERY_MIN..
                                            AppSettings.EINK_REFRESH_EVERY_MAX,
                                        step = 1,
                                        enabled = eInkModeEnabled && eInkRefreshEnabled,
                                        valueText = {
                                            resources.getQuantityString(R.plurals.pages_value, it, it)
                                        },
                                        onValueChange = {
                                            prefs.edit { putInt(AppSettings.KEY_EINK_REFRESH_EVERY, it) }
                                        },
                                    )
                                }
                                item {
                                    SettingsChoicePreference(
                                        title = stringResource(R.string.eink_refresh_color),
                                        iconRes = R.drawable.ic_palette,
                                        options = listOf(
                                            SettingsChoiceOption(
                                                EInkRefreshColor.WHITE.name,
                                                stringResource(R.string.color_white),
                                            ),
                                            SettingsChoiceOption(
                                                EInkRefreshColor.BLACK.name,
                                                stringResource(R.string.color_black),
                                            ),
                                        ),
                                        value = settings.observeAsState(AppSettings.KEY_EINK_REFRESH_COLOR) {
                                            eInkRefreshColor.name
                                        }.value,
                                        enabled = eInkModeEnabled && eInkRefreshEnabled,
                                        onValueChange = {
                                            prefs.edit { putString(AppSettings.KEY_EINK_REFRESH_COLOR, it) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderMangaSettingsPage(
    settings: AppSettings,
    prefs: SharedPreferences,
    readerModeNames: List<String>,
    zoomModeNames: List<String>,
    readerCropNames: List<String>,
    readerOrientationNames: List<String>,
    readerBackgroundNames: List<String>,
    readerAnimationNames: List<String>,
    readerInfoBarLayoutOptions: List<SettingsChoiceOption<ReaderInfoBarLayout>>,
    pagesPreloadNames: List<String>,
    readerControlOptions: List<SettingsChoiceOption<ReaderControl>>,
) {
    val readerInfoBarSettings = resolveReaderInfoBarSettingsState(
        infoBarEnabled = settings.observeAsState(AppSettings.KEY_READER_BAR) {
            isReaderBarEnabled
        }.value,
    )
    ReaderModeSettingsContent(
        state = ReaderModeSettingsUiState(
            mode = settings.observeAsState(AppSettings.KEY_READER_MODE) {
                prefs.getString(AppSettings.KEY_READER_MODE, "") ?: ""
            }.value,
            detectMode = settings.observeAsState(AppSettings.KEY_READER_MODE_DETECT) {
                isReaderModeDetectionEnabled
            }.value,
            background = settings.observeAsState(AppSettings.KEY_READER_BACKGROUND) {
                prefs.getString(AppSettings.KEY_READER_BACKGROUND, ReaderBackground.AUTO.name)
                    ?: ReaderBackground.AUTO.name
            }.value,
        ),
        modeOptions = stringArrayResource(R.array.reader_modes).mapIndexed { index, label ->
            PreferenceChoice(readerModeNames[index], label)
        },
        backgroundOptions = stringArrayResource(R.array.reader_backgrounds).mapIndexed { index, label ->
            PreferenceChoice(readerBackgroundNames[index], label)
        },
        onModeChange = { prefs.edit { putString(AppSettings.KEY_READER_MODE, it) } },
        onDetectModeChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_MODE_DETECT, it) } },
        onBackgroundChange = { prefs.edit { putString(AppSettings.KEY_READER_BACKGROUND, it) } },
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        icons = ReaderModeSettingsIcons(
            mode = painterResource(R.drawable.ic_book_page),
            detectMode = painterResource(R.drawable.ic_auto_fix),
            background = painterResource(R.drawable.ic_images),
        ),
        platformSettings = {
            SettingsChoicePreference(
                title = stringResource(R.string.screen_orientation),
                iconRes = R.drawable.ic_screen_rotation,
                options = stringArrayResource(R.array.screen_orientations).mapIndexed { index, label ->
                    SettingsChoiceOption(readerOrientationNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_ORIENTATION) {
                    prefs.getString(AppSettings.KEY_READER_ORIENTATION, "") ?: ""
                }.value,
                onValueChange = { prefs.edit { putString(AppSettings.KEY_READER_ORIENTATION, it) } },
            )
            SettingsRowDivider()
            SettingsSwitchPreference(
                title = stringResource(R.string.fullscreen_mode),
                summary = stringResource(R.string.reader_fullscreen_summary),
                iconRes = R.drawable.ic_fullscreen,
                checked = settings.observeAsState(AppSettings.KEY_READER_FULLSCREEN) {
                    isReaderFullscreenEnabled
                }.value,
                onCheckedChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_FULLSCREEN, it) } },
            )
        },
        renderDialog = { AndroidPreferenceDialog(it) },
    )

    ReaderZoomSettingsContent(
        state = ReaderZoomSettingsUiState(
            fitMode = settings.observeAsState(AppSettings.KEY_ZOOM_MODE) {
                prefs.getString(AppSettings.KEY_ZOOM_MODE, "") ?: ""
            }.value,
            animation = settings.observeAsState(AppSettings.KEY_READER_ANIMATION) {
                prefs.getString(AppSettings.KEY_READER_ANIMATION, "") ?: ""
            }.value,
        ),
        fitOptions = stringArrayResource(R.array.zoom_modes).mapIndexed { index, label ->
            PreferenceChoice(zoomModeNames[index], label)
        },
        animationOptions = stringArrayResource(R.array.reader_animation).mapIndexed { index, label ->
            PreferenceChoice(readerAnimationNames[index], label)
        },
        crop = ReaderCropSetting.ByReaderType(
            selected = settings.observeAsState(AppSettings.KEY_READER_CROP) {
                prefs.getStringSet(AppSettings.KEY_READER_CROP, emptySet()) ?: emptySet()
            }.value,
            options = stringArrayResource(R.array.reader_crop).mapIndexed { index, label ->
                PreferenceChoice(readerCropNames[index], label)
            },
            onChange = { prefs.edit { putStringSet(AppSettings.KEY_READER_CROP, it) } },
        ),
        onFitChange = { prefs.edit { putString(AppSettings.KEY_ZOOM_MODE, it) } },
        onAnimationChange = { prefs.edit { putString(AppSettings.KEY_READER_ANIMATION, it) } },
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        icons = ReaderZoomSettingsIcons(
            fit = painterResource(R.drawable.ic_zoom_in),
            crop = painterResource(R.drawable.ic_aspect_ratio),
            animation = painterResource(R.drawable.ic_animation),
        ),
        platformZoomControls = {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_zoom_buttons),
                summary = stringResource(R.string.reader_zoom_buttons_summary),
                iconRes = R.drawable.ic_zoom_out,
                checked = settings.observeAsState(AppSettings.KEY_READER_ZOOM_BUTTONS) {
                    prefs.getBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, false)
                }.value,
                onCheckedChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, it) } },
            )
        },
        platformScrollControls = {
            SettingsSwitchPreference(
                title = stringResource(R.string.enable_pull_gesture_title),
                summary = stringResource(R.string.enable_pull_gesture_summary),
                iconRes = R.drawable.ic_gesture_vertical,
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_PULL_GESTURE) {
                    prefs.getBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, false)
                }.value,
                onCheckedChange = { prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, it) } },
            )
        },
        renderDialog = { AndroidPreferenceDialog(it) },
    )

    ReaderWebtoonSettingsContent(
        gaps = settings.observeAsState(AppSettings.KEY_WEBTOON_GAPS) {
            prefs.getBoolean(AppSettings.KEY_WEBTOON_GAPS, false)
        }.value,
        onGapsChange = { prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_GAPS, it) } },
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        gapsIcon = painterResource(R.drawable.ic_line_spacing),
        platformControls = {
            SettingsSwitchPreference(
                title = stringResource(R.string.webtoon_zoom),
                summary = stringResource(R.string.webtoon_zoom_summary),
                iconRes = R.drawable.ic_gesture_vertical,
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_ZOOM) {
                    isWebtoonZoomEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_ZOOM, it) } },
            )
            SettingsRowDivider()
            SettingsSliderPreference(
                title = stringResource(R.string.default_webtoon_zoom_out),
                iconRes = R.drawable.ic_zoom_out,
                value = settings.observeAsState(AppSettings.KEY_WEBTOON_ZOOM_OUT) {
                    try {
                        prefs.getInt(AppSettings.KEY_WEBTOON_ZOOM_OUT, 0)
                    } catch (_: ClassCastException) {
                        prefs.getLong(AppSettings.KEY_WEBTOON_ZOOM_OUT, 0L).toInt().also {
                            prefs.edit { putInt(AppSettings.KEY_WEBTOON_ZOOM_OUT, it) }
                        }
                    }
                }.value,
                valueRange = 0..50,
                step = 10,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_WEBTOON_ZOOM_OUT, it) } },
            )
            SettingsRowDivider()
            SettingsSliderPreference(
                title = stringResource(R.string.webtoon_volume_key_scroll_distance),
                summary = stringResource(R.string.webtoon_volume_key_scroll_distance_summary),
                iconRes = R.drawable.ic_volume_up,
                value = settings.observeAsState(AppSettings.KEY_WEBTOON_VOLUME_KEY_SCROLL_DISTANCE) {
                    webtoonVolumeKeyScrollDistancePercent
                }.value,
                valueRange = AppSettings.MIN_WEBTOON_VOLUME_KEY_SCROLL_DISTANCE..
                    AppSettings.MAX_WEBTOON_VOLUME_KEY_SCROLL_DISTANCE,
                step = 10,
                valueText = { "$it%" },
                onValueChange = {
                    settings.prefs.edit { putInt(AppSettings.KEY_WEBTOON_VOLUME_KEY_SCROLL_DISTANCE, it) }
                },
            )
        },
    )

    SettingsPreferenceGroup(
        title = stringResource(R.string.reader_settings_group_controls),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsMultiChoicePreference(
                title = stringResource(R.string.reader_floating_controls),
                iconRes = R.drawable.ic_reorder_handle,
                values = settings.observeAsState(AppSettings.KEY_READER_CONTROLS) {
                    readerControls
                }.value,
                options = readerControlOptions,
                emptySelectionText = stringResource(R.string.none),
                summary = stringResource(R.string.reader_floating_controls_summary, ReaderControl.MAX_FLOATING_CONTROLS),
                maxSelections = ReaderControl.MAX_FLOATING_CONTROLS,
                onValueChange = { controls ->
                    val limitedControls = ReaderControl.limitFloatingControls(controls)
                    prefs.edit { putStringSet(AppSettings.KEY_READER_CONTROLS, limitedControls.map { it.name }.toSet()) }
                },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_floating_controls_pinned),
                summary = stringResource(R.string.reader_floating_controls_pinned_summary),
                iconRes = R.drawable.ic_pin,
                checked = settings.observeAsState(AppSettings.KEY_READER_FLOATING_CONTROLS_PINNED) {
                    isReaderFloatingControlsPinned
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_FLOATING_CONTROLS_PINNED, it) }
                },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_control_ltr),
                summary = stringResource(R.string.reader_control_ltr_summary),
                iconRes = R.drawable.ic_reader_ltr,
                checked = settings.observeAsState(AppSettings.KEY_READER_CONTROL_LTR) {
                    prefs.getBoolean(AppSettings.KEY_READER_CONTROL_LTR, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CONTROL_LTR, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_info_bar),
                summary = stringResource(R.string.reader_info_bar_summary),
                iconRes = R.drawable.ic_timeline,
                checked = readerInfoBarSettings.enabled,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_BAR, it) } },
            )
        }
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.reader_info_bar_layout),
                iconRes = R.drawable.ic_reorder_handle,
                value = settings.observeAsState(AppSettings.KEY_READER_BAR_LAYOUT) {
                    readerInfoBarLayout
                }.value,
                options = readerInfoBarLayoutOptions,
                enabled = readerInfoBarSettings.layoutEnabled,
                onValueChange = { settings.readerInfoBarLayout = it },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_info_bar_cutout_avoidance),
                summary = stringResource(R.string.reader_info_bar_cutout_avoidance_summary),
                iconRes = R.drawable.ic_fullscreen,
                checked = settings.observeAsState(AppSettings.KEY_READER_BAR_CUTOUT_AVOIDANCE) {
                    isReaderInfoBarCutoutAvoidanceEnabled
                }.value,
                enabled = readerInfoBarSettings.cutoutAvoidanceEnabled,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_BAR_CUTOUT_AVOIDANCE, it) }
                },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_info_bar_transparent),
                iconRes = R.drawable.ic_drawer_menu,
                checked = settings.observeAsState(AppSettings.KEY_READER_BAR_TRANSPARENT) {
                    isReaderBarTransparent
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_BAR_TRANSPARENT, it) } },
            )
        }
    }

    ReaderDisplaySettingsContent(
        state = ReaderDisplaySettingsUiState(
            chapterTitleAtBottom = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM) {
                isReaderChapterTitleAtBottom
            }.value,
            pageNumbers = settings.observeAsState(AppSettings.KEY_PAGES_NUMBERS) {
                prefs.getBoolean(AppSettings.KEY_PAGES_NUMBERS, false)
            }.value,
        ),
        onChapterTitleAtBottomChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM, it) } },
        onPageNumbersChange = { prefs.edit { putBoolean(AppSettings.KEY_PAGES_NUMBERS, it) } },
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        icons = ReaderDisplaySettingsIcons(
            chapterTitleAtBottom = painterResource(R.drawable.ic_format_size),
            pageNumbers = painterResource(R.drawable.ic_list_detailed),
        ),
        platformControlsBefore = {
            SettingsSwitchPreference(
                title = stringResource(R.string.keep_screen_on),
                summary = stringResource(R.string.keep_screen_on_summary),
                iconRes = R.drawable.ic_battery_outline,
                checked = settings.observeAsState(AppSettings.KEY_READER_SCREEN_ON) {
                    isReaderKeepScreenOn
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_SCREEN_ON, it) } },
            )
            SettingsRowDivider()
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_multitask),
                summary = stringResource(R.string.reader_multitask_summary),
                iconRes = R.drawable.ic_picture_in_picture,
                checked = settings.observeAsState(AppSettings.KEY_READER_MULTITASK) {
                    prefs.getBoolean(AppSettings.KEY_READER_MULTITASK, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_MULTITASK, it) } },
            )
            SettingsRowDivider()
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_chapter_toast),
                summary = stringResource(R.string.reader_chapter_toast_summary),
                iconRes = R.drawable.ic_comment,
                checked = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TOAST) {
                    isReaderChapterToastEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_TOAST, it) } },
            )
            SettingsRowDivider()
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_chapter_break),
                summary = stringResource(R.string.reader_chapter_break_summary),
                iconRes = R.drawable.ic_next,
                checked = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_BREAK) {
                    isReaderChapterBreakEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_BREAK, it) } },
            )
        },
        platformControlsAfter = {
            SettingsSwitchPreference(
                title = stringResource(R.string.enhanced_colors),
                summary = stringResource(R.string.enhanced_colors_summary),
                iconRes = R.drawable.ic_palette,
                checked = settings.observeAsState(AppSettings.KEY_32BIT_COLOR) {
                    prefs.getBoolean(AppSettings.KEY_32BIT_COLOR, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_32BIT_COLOR, it) } },
            )
        },
    )

    SettingsPreferenceGroup(
        title = stringResource(R.string.pages_saving),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsSliderPreference(
                title = stringResource(R.string.page_save_manga_title_length),
                summary = stringResource(R.string.page_save_manga_title_length_summary),
                iconRes = R.drawable.ic_save,
                value = settings.observeAsState(AppSettings.KEY_PAGES_SAVE_MANGA_TITLE_LENGTH) {
                    settings.pagesSaveMangaTitleLength
                }.value,
                valueRange = AppSettings.PAGES_SAVE_MANGA_TITLE_LENGTH_MIN..
                    AppSettings.PAGES_SAVE_MANGA_TITLE_LENGTH_MAX,
                step = 4,
                valueText = { it.toString() },
                onValueChange = { settings.pagesSaveMangaTitleLength = it },
            )
        }
    }

    SettingsCollapsiblePreferenceGroup(
        title = stringResource(R.string.reader_settings_group_performance),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        initiallyExpanded = false,
    ) {
        item("avif_animation_memory_limit_mb") {
            val memoryLimit = settings.observeAsState(AppSettings.KEY_AVIF_ANIMATION_MEMORY_LIMIT) {
                avifAnimationMemoryLimitMb
            }.value
            val profile = settings.avifAnimationDeviceProfile
            val budgets = (listOf(16, 32, 64, 96, 128, 192, 256, 384, 512) + memoryLimit)
                .filter { it in 16..profile.maxMemoryLimitMb }.distinct().sorted()
            SettingsChoicePreference(
                title = stringResource(R.string.avif_animation_memory_limit),
                summary = stringResource(R.string.avif_animation_memory_limit_summary),
                value = memoryLimit,
                options = listOf(
                    SettingsChoiceOption(0, stringResource(
                        R.string.avif_animation_memory_auto,
                        profile.recommendedMemoryLimitMb,
                    )),
                ) + budgets.map { SettingsChoiceOption(it, "$it MiB") },
                onValueChange = { settings.avifAnimationMemoryLimitMb = it },
            )
        }
        item("avif_animation_downsampling") {
            SettingsSwitchPreference(
                title = stringResource(R.string.avif_animation_downsampling),
                summary = stringResource(R.string.avif_animation_downsampling_summary),
                checked = settings.observeAsState(AppSettings.KEY_AVIF_ANIMATION_DOWNSAMPLING) {
                    isAvifAnimationDownsamplingAllowed
                }.value,
                onCheckedChange = { settings.isAvifAnimationDownsamplingAllowed = it },
            )
        }
        item {
            SettingsActionPreference(
                title = stringResource(R.string.avif_animation_recommended),
                summary = stringResource(
                    R.string.avif_animation_recommended_summary,
                    settings.avifAnimationDeviceProfile.recommendedMemoryLimitMb,
                ),
                showChevron = false,
                onClick = settings::resetAvifAnimationPolicy,
            )
        }
        item("reader_optimize") {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_optimize),
                summary = stringResource(R.string.reader_optimize_summary),
                iconRes = R.drawable.ic_bolt,
                checked = settings.observeAsState(AppSettings.KEY_READER_OPTIMIZE) {
                    prefs.getBoolean(AppSettings.KEY_READER_OPTIMIZE, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_OPTIMIZE, it) } },
            )
        }
        item("reader_reduce_page_preloading") {
            ReaderPreloadSettingsRow(
                reduced = settings.observeAsState(AppSettings.KEY_READER_REDUCE_PRELOAD) {
                    prefs.getBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, false)
                }.value,
                onReducedChange = { prefs.edit { putBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, it) } },
                icon = painterResource(R.drawable.ic_off_small),
            )
        }
        item("reader_experimental_scene_engine") {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_experimental_scene_engine),
                summary = stringResource(R.string.reader_experimental_scene_engine_summary),
                iconRes = R.drawable.ic_bolt,
                checked = settings.observeAsState(AppSettings.KEY_READER_EXPERIMENTAL_SCENE_ENGINE) {
                    prefs.getBoolean(AppSettings.KEY_READER_EXPERIMENTAL_SCENE_ENGINE, false)
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_EXPERIMENTAL_SCENE_ENGINE, it) }
                },
            )
        }
        item("reader_experimental_paged_scene_engine") {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_experimental_paged_scene_engine),
                summary = stringResource(R.string.reader_experimental_paged_scene_engine_summary),
                iconRes = R.drawable.ic_book_page,
                checked = settings.observeAsState(AppSettings.KEY_READER_EXPERIMENTAL_PAGED_SCENE_ENGINE) {
                    prefs.getBoolean(AppSettings.KEY_READER_EXPERIMENTAL_PAGED_SCENE_ENGINE, false)
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_EXPERIMENTAL_PAGED_SCENE_ENGINE, it) }
                },
            )
        }
        item("pages_preload") {
            SettingsChoicePreference(
                title = stringResource(R.string.preload_pages),
                iconRes = R.drawable.ic_download,
                options = stringArrayResource(R.array.network_policy).mapIndexed { index, label ->
                    SettingsChoiceOption(pagesPreloadNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_PAGES_PRELOAD) {
                    prefs.getString(AppSettings.KEY_PAGES_PRELOAD, "") ?: ""
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_PAGES_PRELOAD, it) } },
            )
        }
        item("reader_threads") {
            SettingsSliderPreference(
                title = stringResource(R.string.reader_threads),
                summary = stringResource(R.string.reader_threads_summary),
                iconRes = R.drawable.ic_network_cellular,
                value = settings.observeAsState(AppSettings.KEY_READER_THREADS) { readerThreads }.value,
                valueRange = 1..10,
                step = 1,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_READER_THREADS, it) } },
            )
        }
        item("reader_prefetch_limit") {
            SettingsSliderPreference(
                title = stringResource(R.string.prefetch_limit),
                summary = stringResource(R.string.prefetch_limit_summary),
                iconRes = R.drawable.ic_fast_forward,
                value = settings.observeAsState(AppSettings.KEY_READER_PREFETCH_LIMIT) { readerPrefetchLimit }.value,
                valueRange = 1..20,
                step = 1,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_READER_PREFETCH_LIMIT, it) } },
            )
        }
    }
}
