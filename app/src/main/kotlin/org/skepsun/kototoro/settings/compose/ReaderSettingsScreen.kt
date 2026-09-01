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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.EInkRefreshColor
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.ReaderInfoBarLayout
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.ReadingMode
import kotlinx.coroutines.launch

private const val READER_SETTINGS_COMMON_PAGE = 0
private const val READER_SETTINGS_MANGA_PAGE = 1
private const val READER_SETTINGS_NOVEL_PAGE = 2
private const val READER_SETTINGS_PAGE_COUNT = 3

@Composable
fun ReaderSettingsScreen(
    settings: AppSettings,
    onReaderTapActionsClick: () -> Unit,
    onReaderAiSettingsEntryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs
    val context = LocalContext.current
    val resources = context.resources
    var novelSettings by remember(context) { mutableStateOf(NovelReaderSettings.load(context)) }
    fun updateNovelSettings(transform: NovelReaderSettings.() -> NovelReaderSettings) {
        novelSettings = novelSettings.transform().normalized().also { it.save(context) }
    }

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
    )
    val pagesPreloadNames = listOf("1", "2", "0")
    val eInkModeEnabled = settings.observeAsState(AppSettings.KEY_EINK_MODE) { isEInkModeEnabled }.value
    val eInkRefreshEnabled = settings.observeAsState(AppSettings.KEY_EINK_REFRESH) { isEInkRefreshEnabled }.value
    val readerControlOptions = listOf(
        SettingsChoiceOption(ReaderControl.SCREEN_ROTATION, stringResource(R.string.screen_orientation)),
        SettingsChoiceOption(ReaderControl.SAVE_PAGE, stringResource(R.string.save_page)),
        SettingsChoiceOption(ReaderControl.TIMER, stringResource(R.string.automatic_scroll)),
        SettingsChoiceOption(ReaderControl.BOOKMARK, stringResource(R.string.bookmark_add)),
        SettingsChoiceOption(ReaderControl.TRANSLATE, stringResource(R.string.novel_translate)),
        SettingsChoiceOption(ReaderControl.DOWNLOAD, stringResource(R.string.download)),
    )
    val novelReaderControlOptions = listOf(
        SettingsChoiceOption(ReaderControl.BOOKMARK, stringResource(R.string.bookmark_add)),
        SettingsChoiceOption(ReaderControl.TRANSLATE, stringResource(R.string.novel_translate)),
    )
    val pagerState = rememberPagerState(
        initialPage = READER_SETTINGS_COMMON_PAGE,
        pageCount = { READER_SETTINGS_PAGE_COUNT },
    )
    val coroutineScope = rememberCoroutineScope()
    val pageLabels = listOf(
        stringResource(R.string.reader_settings_common_tab),
        stringResource(R.string.manga),
        stringResource(R.string.novel),
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
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(label) },
                    )
                }
            }
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
                            onReaderAiSettingsEntryClick = onReaderAiSettingsEntryClick,
                        )

                        READER_SETTINGS_NOVEL_PAGE -> {
                            SettingsPreferenceGroup(
                                title = stringResource(R.string.novel),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                item {
                                    SettingsChoicePreference(
                                        title = stringResource(R.string.novel_reading_mode),
                                        iconRes = R.drawable.ic_reader_vertical,
                                        value = novelSettings.readingMode.name,
                                        options = listOf(
                                            SettingsChoiceOption(
                                                ReadingMode.PAGED.name,
                                                stringResource(R.string.novel_mode_paged),
                                            ),
                                            SettingsChoiceOption(
                                                ReadingMode.SCROLL.name,
                                                stringResource(R.string.novel_mode_scroll),
                                            ),
                                        ),
                                        onValueChange = { value ->
                                            updateNovelSettings { copy(readingMode = ReadingMode.valueOf(value)) }
                                        },
                                    )
                                }

                                item {
                                    SettingsMultiChoicePreference(
                                        title = stringResource(R.string.reader_floating_controls),
                                        iconRes = R.drawable.ic_reorder_handle,
                                        values = settings.observeAsState(AppSettings.KEY_NOVEL_READER_CONTROLS) {
                                            novelReaderControls
                                        }.value,
                                        options = novelReaderControlOptions,
                                        emptySelectionText = stringResource(R.string.none),
                                        summary = stringResource(
                                            R.string.reader_floating_controls_summary,
                                            ReaderControl.NOVEL_FLOATING.size,
                                        ),
                                        maxSelections = ReaderControl.NOVEL_FLOATING.size,
                                        onValueChange = { controls ->
                                            val novelControls = ReaderControl.limitNovelFloatingControls(controls)
                                            prefs.edit {
                                                putStringSet(
                                                    AppSettings.KEY_NOVEL_READER_CONTROLS,
                                                    novelControls.map { it.name }.toSet(),
                                                )
                                            }
                                        },
                                    )
                                }

                                item {
                                    SettingsChoicePreference(
                                        title = stringResource(R.string.novel_page_turn_animation),
                                        iconRes = R.drawable.ic_animation,
                                        value = novelSettings.pageTurnAnimation.name,
                                        options = listOf(
                                            SettingsChoiceOption(
                                                NovelPageTurnAnimation.SLIDE.name,
                                                stringResource(R.string.novel_page_turn_slide),
                                            ),
                                            SettingsChoiceOption(
                                                NovelPageTurnAnimation.SIMULATION.name,
                                                stringResource(R.string.novel_page_turn_simulation),
                                            ),
                                        ),
                                        enabled = novelSettings.readingMode == ReadingMode.PAGED,
                                        onValueChange = { value ->
                                            updateNovelSettings {
                                                copy(pageTurnAnimation = NovelPageTurnAnimation.valueOf(value))
                                            }
                                        },
                                    )
                                }

                                item {
                                    SettingsChoicePreference(
                                        title = stringResource(R.string.novel_theme_preset),
                                        iconRes = R.drawable.ic_palette,
                                        value = novelSettings.themePreset.name,
                                        options = listOf(
                                            SettingsChoiceOption(
                                                NovelReaderThemePreset.PAPER.name,
                                                stringResource(R.string.novel_theme_paper),
                                            ),
                                            SettingsChoiceOption(
                                                NovelReaderThemePreset.SEPIA.name,
                                                stringResource(R.string.novel_theme_sepia),
                                            ),
                                            SettingsChoiceOption(
                                                NovelReaderThemePreset.MOSS.name,
                                                stringResource(R.string.novel_theme_moss),
                                            ),
                                            SettingsChoiceOption(
                                                NovelReaderThemePreset.SLATE.name,
                                                stringResource(R.string.novel_theme_slate),
                                            ),
                                        ),
                                        onValueChange = { value ->
                                            updateNovelSettings { copy(themePreset = NovelReaderThemePreset.valueOf(value)) }
                                        },
                                    )
                                }

                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.novel_font_size),
                                        iconRes = R.drawable.ic_format_size,
                                        value = (novelSettings.fontSizeSp * 2).toInt(),
                                        valueRange = (NovelReaderSettings.FONT_SIZE_RANGE.start * 2).toInt()..
                                            (NovelReaderSettings.FONT_SIZE_RANGE.endInclusive * 2).toInt(),
                                        step = 1,
                                        valueText = { "%.1fsp".format(it / 2f) },
                                        onValueChange = { value -> updateNovelSettings { copy(fontSizeSp = value / 2f) } },
                                    )
                                }

                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.novel_line_spacing),
                                        iconRes = R.drawable.ic_line_spacing,
                                        value = (novelSettings.lineSpacing * 10).toInt(),
                                        valueRange = (NovelReaderSettings.LINE_SPACING_RANGE.start * 10).toInt()..
                                            (NovelReaderSettings.LINE_SPACING_RANGE.endInclusive * 10).toInt(),
                                        step = 1,
                                        valueText = { "%.1f".format(it / 10f) },
                                        onValueChange = { value -> updateNovelSettings { copy(lineSpacing = value / 10f) } },
                                    )
                                }

                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.novel_paragraph_spacing),
                                        iconRes = R.drawable.ic_line_spacing,
                                        value = novelSettings.paragraphSpacingLines,
                                        valueRange = 0..3,
                                        step = 1,
                                        valueText = { resources.getString(R.string.novel_paragraph_spacing_value, it) },
                                        onValueChange = { value -> updateNovelSettings { copy(paragraphSpacing = value.toFloat()) } },
                                    )
                                }

                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.novel_margin_horizontal),
                                        iconRes = R.drawable.ic_straighten,
                                        value = novelSettings.marginHorizontal,
                                        valueRange = NovelReaderSettings.MARGIN_RANGE,
                                        step = NovelReaderSettings.MARGIN_STEP,
                                        valueText = { "${it}dp" },
                                        onValueChange = { value -> updateNovelSettings { copy(marginHorizontal = value) } },
                                    )
                                }

                                item {
                                    SettingsSliderPreference(
                                        title = stringResource(R.string.novel_margin_vertical),
                                        iconRes = R.drawable.ic_straighten,
                                        value = novelSettings.marginVertical,
                                        valueRange = NovelReaderSettings.MARGIN_RANGE,
                                        step = NovelReaderSettings.MARGIN_STEP,
                                        valueText = { "${it}dp" },
                                        onValueChange = { value -> updateNovelSettings { copy(marginVertical = value) } },
                                    )
                                }

                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.novel_dual_page_mode),
                                        iconRes = R.drawable.ic_view_column,
                                        checked = novelSettings.enableDualPage,
                                        onCheckedChange = { enabled -> updateNovelSettings { copy(enableDualPage = enabled) } },
                                    )
                                }

                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.novel_fullscreen_mode),
                                        iconRes = R.drawable.ic_fullscreen,
                                        checked = novelSettings.enableFullscreen,
                                        onCheckedChange = { enabled -> updateNovelSettings { copy(enableFullscreen = enabled) } },
                                    )
                                }

                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.novel_show_reading_status),
                                        iconRes = R.drawable.ic_progress_marker,
                                        checked = novelSettings.showReadingStatus,
                                        onCheckedChange = { enabled -> updateNovelSettings { copy(showReadingStatus = enabled) } },
                                    )
                                }

                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.novel_transparent_status_bar),
                                        iconRes = R.drawable.ic_drawer_menu,
                                        checked = novelSettings.isReadingStatusTransparent,
                                        enabled = novelSettings.showReadingStatus,
                                        onCheckedChange = { enabled ->
                                            updateNovelSettings { copy(isReadingStatusTransparent = enabled) }
                                        },
                                    )
                                }

                                item {
                                    SettingsSwitchPreference(
                                        title = stringResource(R.string.novel_first_line_indent),
                                        iconRes = R.drawable.ic_read,
                                        checked = novelSettings.enableParagraphIndent,
                                        onCheckedChange = { enabled -> updateNovelSettings { copy(enableParagraphIndent = enabled) } },
                                    )
                                }

                                item {
                                    SettingsChoicePreference(
                                        title = stringResource(R.string.novel_translation_display_mode),
                                        iconRes = R.drawable.ic_translate,
                                        value = novelSettings.translationDisplayMode.name,
                                        options = listOf(
                                            SettingsChoiceOption(
                                                NovelTranslationDisplayMode.TRANSLATION_ONLY.name,
                                                stringResource(R.string.novel_translation_only),
                                            ),
                                            SettingsChoiceOption(
                                                NovelTranslationDisplayMode.BILINGUAL.name,
                                                stringResource(R.string.novel_translation_bilingual),
                                            ),
                                        ),
                                        onValueChange = { value ->
                                            updateNovelSettings {
                                                copy(translationDisplayMode = NovelTranslationDisplayMode.valueOf(value))
                                            }
                                        },
                                    )
                                }
                            }
                        }

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
    onReaderAiSettingsEntryClick: () -> Unit,
) {
    SettingsPreferenceGroup(
        title = "",
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            SettingsActionPreference(
                title = stringResource(R.string.ai_settings),
                summary = stringResource(R.string.ai_settings_entry_summary),
                iconRes = R.drawable.ic_auto_fix,
                onClick = onReaderAiSettingsEntryClick,
            )
        }
    }

    SettingsPreferenceGroup(
        title = stringResource(R.string.reader_settings_group_reading_mode),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.default_mode),
                iconRes = R.drawable.ic_book_page,
                options = stringArrayResource(R.array.reader_modes).mapIndexed { index, label ->
                    SettingsChoiceOption(readerModeNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_MODE) {
                    prefs.getString(AppSettings.KEY_READER_MODE, "") ?: ""
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_MODE, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.detect_reader_mode),
                summary = stringResource(R.string.detect_reader_mode_summary),
                iconRes = R.drawable.ic_auto_fix,
                checked = settings.observeAsState(AppSettings.KEY_READER_MODE_DETECT) {
                    isReaderModeDetectionEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_MODE_DETECT, it) } },
            )
        }
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.reader_background),
                iconRes = R.drawable.ic_images,
                options = stringArrayResource(R.array.reader_backgrounds).mapIndexed { index, label ->
                    SettingsChoiceOption(readerBackgroundNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_BACKGROUND) {
                    prefs.getString(AppSettings.KEY_READER_BACKGROUND, ReaderBackground.AUTO.name)
                        ?: ReaderBackground.AUTO.name
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_BACKGROUND, it) } },
            )
        }
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.screen_orientation),
                iconRes = R.drawable.ic_screen_rotation,
                options = stringArrayResource(R.array.screen_orientations).mapIndexed { index, label ->
                    SettingsChoiceOption(readerOrientationNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_ORIENTATION) {
                    prefs.getString(AppSettings.KEY_READER_ORIENTATION, "") ?: ""
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_ORIENTATION, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.fullscreen_mode),
                summary = stringResource(R.string.reader_fullscreen_summary),
                iconRes = R.drawable.ic_fullscreen,
                checked = settings.observeAsState(AppSettings.KEY_READER_FULLSCREEN) {
                    isReaderFullscreenEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_FULLSCREEN, it) } },
            )
        }
    }

    SettingsPreferenceGroup(
        title = stringResource(R.string.reader_settings_group_zoom_scroll),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.scale_mode),
                iconRes = R.drawable.ic_zoom_in,
                options = stringArrayResource(R.array.zoom_modes).mapIndexed { index, label ->
                    SettingsChoiceOption(zoomModeNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_ZOOM_MODE) {
                    prefs.getString(AppSettings.KEY_ZOOM_MODE, "") ?: ""
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_ZOOM_MODE, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_zoom_buttons),
                summary = stringResource(R.string.reader_zoom_buttons_summary),
                iconRes = R.drawable.ic_zoom_out,
                checked = settings.observeAsState(AppSettings.KEY_READER_ZOOM_BUTTONS) {
                    prefs.getBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, it) } },
            )
        }
        item {
            SettingsMultiChoicePreference(
                title = stringResource(R.string.crop_pages),
                iconRes = R.drawable.ic_aspect_ratio,
                options = stringArrayResource(R.array.reader_crop).mapIndexed { index, label ->
                    SettingsChoiceOption(readerCropNames[index], label)
                },
                values = settings.observeAsState(AppSettings.KEY_READER_CROP) {
                    prefs.getStringSet(AppSettings.KEY_READER_CROP, emptySet()) ?: emptySet()
                }.value,
                emptySelectionText = stringResource(R.string.none),
                onValueChange = { settings.prefs.edit { putStringSet(AppSettings.KEY_READER_CROP, it) } },
            )
        }
        item {
            SettingsChoicePreference(
                title = stringResource(R.string.pages_animation),
                iconRes = R.drawable.ic_animation,
                options = stringArrayResource(R.array.reader_animation).mapIndexed { index, label ->
                    SettingsChoiceOption(readerAnimationNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_ANIMATION) {
                    prefs.getString(AppSettings.KEY_READER_ANIMATION, "") ?: ""
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_ANIMATION, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.enable_pull_gesture_title),
                summary = stringResource(R.string.enable_pull_gesture_summary),
                iconRes = R.drawable.ic_gesture_vertical,
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_PULL_GESTURE) {
                    prefs.getBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, it) } },
            )
        }
    }

    SettingsPreferenceGroup(
        title = stringResource(R.string.reader_settings_group_webtoon),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.webtoon_zoom),
                summary = stringResource(R.string.webtoon_zoom_summary),
                iconRes = R.drawable.ic_gesture_vertical,
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_ZOOM) {
                    isWebtoonZoomEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_ZOOM, it) } },
            )
        }
        item {
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
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.webtoon_gaps),
                summary = stringResource(R.string.webtoon_gaps_summary),
                iconRes = R.drawable.ic_line_spacing,
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_GAPS) {
                    prefs.getBoolean(AppSettings.KEY_WEBTOON_GAPS, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_GAPS, it) } },
            )
        }
    }

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
                checked = settings.observeAsState(AppSettings.KEY_READER_BAR) {
                    isReaderBarEnabled
                }.value,
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
                enabled = settings.isReaderBarEnabled,
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
                enabled = settings.isReaderBarEnabled,
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

    SettingsPreferenceGroup(
        title = stringResource(R.string.reader_settings_group_display),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.keep_screen_on),
                summary = stringResource(R.string.keep_screen_on_summary),
                iconRes = R.drawable.ic_battery_outline,
                checked = settings.observeAsState(AppSettings.KEY_READER_SCREEN_ON) {
                    isReaderKeepScreenOn
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_SCREEN_ON, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_multitask),
                summary = stringResource(R.string.reader_multitask_summary),
                iconRes = R.drawable.ic_picture_in_picture,
                checked = settings.observeAsState(AppSettings.KEY_READER_MULTITASK) {
                    prefs.getBoolean(AppSettings.KEY_READER_MULTITASK, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_MULTITASK, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_chapter_toast),
                summary = stringResource(R.string.reader_chapter_toast_summary),
                iconRes = R.drawable.ic_comment,
                checked = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TOAST) {
                    isReaderChapterToastEnabled
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_TOAST, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_chapter_title_at_bottom),
                summary = stringResource(R.string.reader_chapter_title_at_bottom_summary),
                iconRes = R.drawable.ic_format_size,
                checked = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM) {
                    isReaderChapterTitleAtBottom
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_TITLE_BOTTOM, it) }
                },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.show_pages_numbers),
                summary = stringResource(R.string.show_pages_numbers_summary),
                iconRes = R.drawable.ic_list_detailed,
                checked = settings.observeAsState(AppSettings.KEY_PAGES_NUMBERS) {
                    prefs.getBoolean(AppSettings.KEY_PAGES_NUMBERS, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_PAGES_NUMBERS, it) } },
            )
        }
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.enhanced_colors),
                summary = stringResource(R.string.enhanced_colors_summary),
                iconRes = R.drawable.ic_palette,
                checked = settings.observeAsState(AppSettings.KEY_32BIT_COLOR) {
                    prefs.getBoolean(AppSettings.KEY_32BIT_COLOR, false)
                }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_32BIT_COLOR, it) } },
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
        item {
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
        item {
            SettingsSwitchPreference(
                title = stringResource(R.string.reader_reduce_page_preloading),
                summary = stringResource(R.string.reader_reduce_page_preloading_summary),
                iconRes = R.drawable.ic_off_small,
                checked = settings.observeAsState(AppSettings.KEY_READER_REDUCE_PRELOAD) {
                    prefs.getBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, false)
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, it) }
                },
            )
        }
        item {
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
        item {
            SettingsSliderPreference(
                title = stringResource(R.string.download_threads),
                summary = stringResource(R.string.download_threads_summary),
                iconRes = R.drawable.ic_network_cellular,
                value = settings.observeAsState(AppSettings.KEY_READER_THREADS) { readerThreads }.value,
                valueRange = 1..10,
                step = 1,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_READER_THREADS, it) } },
            )
        }
        item {
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
