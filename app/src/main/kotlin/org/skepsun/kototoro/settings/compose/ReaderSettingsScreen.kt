package org.skepsun.kototoro.settings.compose

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun ReaderSettingsScreen(
    settings: AppSettings,
    onReaderTapActionsClick: () -> Unit,
    onReaderAiSettingsEntryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = settings.prefs

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
    val pagesPreloadNames = listOf("1", "2", "0")
    val readerControlOptions = listOf(
        SettingsChoiceOption(ReaderControl.SCREEN_ROTATION, stringResource(R.string.screen_orientation)),
        SettingsChoiceOption(ReaderControl.SAVE_PAGE, stringResource(R.string.save_page)),
        SettingsChoiceOption(ReaderControl.TIMER, stringResource(R.string.automatic_scroll)),
        SettingsChoiceOption(ReaderControl.BOOKMARK, stringResource(R.string.bookmark_add)),
        SettingsChoiceOption(ReaderControl.TRANSLATE, stringResource(R.string.novel_translate)),
        SettingsChoiceOption(ReaderControl.DOWNLOAD, stringResource(R.string.download)),
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceSection(
                title = stringResource(R.string.reader_settings),
                modifier = Modifier.fillMaxWidth(),
            ) {
            SettingsChoicePreference(
                title = stringResource(R.string.default_mode),
                options = stringArrayResource(R.array.reader_modes).mapIndexed { index, label ->
                    SettingsChoiceOption(readerModeNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_MODE) { prefs.getString(AppSettings.KEY_READER_MODE, "") ?: "" }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_MODE, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.detect_reader_mode),
                summary = stringResource(R.string.detect_reader_mode_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_MODE_DETECT) { prefs.getBoolean(AppSettings.KEY_READER_MODE_DETECT, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_MODE_DETECT, it) } }
            )

            SettingsChoicePreference(
                title = stringResource(R.string.scale_mode),
                options = stringArrayResource(R.array.zoom_modes).mapIndexed { index, label ->
                    SettingsChoiceOption(zoomModeNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_ZOOM_MODE) { prefs.getString(AppSettings.KEY_ZOOM_MODE, "") ?: "" }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_ZOOM_MODE, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_zoom_buttons),
                summary = stringResource(R.string.reader_zoom_buttons_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_ZOOM_BUTTONS) { prefs.getBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_ZOOM_BUTTONS, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.webtoon_zoom),
                summary = stringResource(R.string.webtoon_zoom_summary),
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_ZOOM) { prefs.getBoolean(AppSettings.KEY_WEBTOON_ZOOM, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_ZOOM, it) } }
            )

            SettingsSliderPreference(
                title = stringResource(R.string.default_webtoon_zoom_out),
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
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_WEBTOON_ZOOM_OUT, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.webtoon_gaps),
                summary = stringResource(R.string.webtoon_gaps_summary),
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_GAPS) { prefs.getBoolean(AppSettings.KEY_WEBTOON_GAPS, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_GAPS, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_control_labels),
                summary = stringResource(R.string.reader_control_labels_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_CONTROL_LABELS) {
                    isReaderControlLabelsEnabled
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CONTROL_LABELS, it) }
                },
            )

            SettingsMultiChoicePreference(
                title = stringResource(R.string.reader_floating_controls),
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

            SettingsActionPreference(
                title = stringResource(R.string.reader_actions),
                summary = stringResource(R.string.reader_actions_summary),
                onClick = onReaderTapActionsClick
            )

            SettingsActionPreference(
                title = stringResource(R.string.ai_settings),
                summary = stringResource(R.string.ai_settings_entry_summary),
                onClick = onReaderAiSettingsEntryClick
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_control_ltr),
                summary = stringResource(R.string.reader_control_ltr_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_CONTROL_LTR) { prefs.getBoolean(AppSettings.KEY_READER_CONTROL_LTR, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CONTROL_LTR, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.switch_pages_volume_buttons),
                summary = stringResource(R.string.switch_pages_volume_buttons_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_VOLUME_BUTTONS) { prefs.getBoolean(AppSettings.KEY_READER_VOLUME_BUTTONS, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_VOLUME_BUTTONS, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_navigation_inverted),
                summary = stringResource(R.string.reader_navigation_inverted_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_NAVIGATION_INVERTED) { prefs.getBoolean(AppSettings.KEY_READER_NAVIGATION_INVERTED, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_NAVIGATION_INVERTED, it) } }
            )

            SettingsChoicePreference(
                title = stringResource(R.string.pages_animation),
                options = stringArrayResource(R.array.reader_animation).mapIndexed { index, label ->
                    SettingsChoiceOption(readerAnimationNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_ANIMATION) { prefs.getString(AppSettings.KEY_READER_ANIMATION, "") ?: "" }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_ANIMATION, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.enable_pull_gesture_title),
                summary = stringResource(R.string.enable_pull_gesture_summary),
                checked = settings.observeAsState(AppSettings.KEY_WEBTOON_PULL_GESTURE) { prefs.getBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_WEBTOON_PULL_GESTURE, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.enhanced_colors),
                summary = stringResource(R.string.enhanced_colors_summary),
                checked = settings.observeAsState(AppSettings.KEY_32BIT_COLOR) { prefs.getBoolean(AppSettings.KEY_32BIT_COLOR, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_32BIT_COLOR, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_optimize),
                summary = stringResource(R.string.reader_optimize_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_OPTIMIZE) { prefs.getBoolean(AppSettings.KEY_READER_OPTIMIZE, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_OPTIMIZE, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_reduce_page_preloading),
                summary = stringResource(R.string.reader_reduce_page_preloading_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_REDUCE_PRELOAD) {
                    prefs.getBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, false)
                }.value,
                onCheckedChange = {
                    settings.prefs.edit { putBoolean(AppSettings.KEY_READER_REDUCE_PRELOAD, it) }
                }
            )

            SettingsMultiChoicePreference(
                title = stringResource(R.string.crop_pages),
                options = stringArrayResource(R.array.reader_crop).mapIndexed { index, label ->
                    SettingsChoiceOption(readerCropNames[index], label)
                },
                values = settings.observeAsState(AppSettings.KEY_READER_CROP) { prefs.getStringSet(AppSettings.KEY_READER_CROP, emptySet()) ?: emptySet() }.value,
                emptySelectionText = stringResource(R.string.none),
                onValueChange = { settings.prefs.edit { putStringSet(AppSettings.KEY_READER_CROP, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.fullscreen_mode),
                summary = stringResource(R.string.reader_fullscreen_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_FULLSCREEN) { prefs.getBoolean(AppSettings.KEY_READER_FULLSCREEN, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_FULLSCREEN, it) } }
            )

            SettingsChoicePreference(
                title = stringResource(R.string.screen_orientation),
                options = stringArrayResource(R.array.screen_orientations).mapIndexed { index, label ->
                    SettingsChoiceOption(readerOrientationNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_ORIENTATION) { prefs.getString(AppSettings.KEY_READER_ORIENTATION, "") ?: "" }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_ORIENTATION, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.keep_screen_on),
                summary = stringResource(R.string.keep_screen_on_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_SCREEN_ON) { prefs.getBoolean(AppSettings.KEY_READER_SCREEN_ON, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_SCREEN_ON, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_multitask),
                summary = stringResource(R.string.reader_multitask_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_MULTITASK) { prefs.getBoolean(AppSettings.KEY_READER_MULTITASK, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_MULTITASK, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_info_bar),
                summary = stringResource(R.string.reader_info_bar_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_BAR) { prefs.getBoolean(AppSettings.KEY_READER_BAR, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_BAR, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_info_bar_transparent),
                checked = settings.observeAsState(AppSettings.KEY_READER_BAR_TRANSPARENT) { prefs.getBoolean(AppSettings.KEY_READER_BAR_TRANSPARENT, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_BAR_TRANSPARENT, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.reader_chapter_toast),
                summary = stringResource(R.string.reader_chapter_toast_summary),
                checked = settings.observeAsState(AppSettings.KEY_READER_CHAPTER_TOAST) { prefs.getBoolean(AppSettings.KEY_READER_CHAPTER_TOAST, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_READER_CHAPTER_TOAST, it) } }
            )

            SettingsChoicePreference(
                title = stringResource(R.string.reader_background),
                options = stringArrayResource(R.array.reader_backgrounds).mapIndexed { index, label ->
                    SettingsChoiceOption(readerBackgroundNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_READER_BACKGROUND) {
                    prefs.getString(AppSettings.KEY_READER_BACKGROUND, ReaderBackground.AUTO.name) ?: ReaderBackground.AUTO.name
                }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_READER_BACKGROUND, it) } }
            )

            SettingsSwitchPreference(
                title = stringResource(R.string.show_pages_numbers),
                summary = stringResource(R.string.show_pages_numbers_summary),
                checked = settings.observeAsState(AppSettings.KEY_PAGES_NUMBERS) { prefs.getBoolean(AppSettings.KEY_PAGES_NUMBERS, false) }.value,
                onCheckedChange = { settings.prefs.edit { putBoolean(AppSettings.KEY_PAGES_NUMBERS, it) } }
            )

            SettingsChoicePreference(
                title = stringResource(R.string.preload_pages),
                options = stringArrayResource(R.array.network_policy).mapIndexed { index, label ->
                    SettingsChoiceOption(pagesPreloadNames[index], label)
                },
                value = settings.observeAsState(AppSettings.KEY_PAGES_PRELOAD) { prefs.getString(AppSettings.KEY_PAGES_PRELOAD, "") ?: "" }.value,
                onValueChange = { settings.prefs.edit { putString(AppSettings.KEY_PAGES_PRELOAD, it) } }
            )

            SettingsSliderPreference(
                title = stringResource(R.string.download_threads),
                summary = stringResource(R.string.download_threads_summary),
                value = settings.observeAsState(AppSettings.KEY_READER_THREADS) { readerThreads }.value,
                valueRange = 1..10,
                step = 1,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_READER_THREADS, it) } }
            )

            SettingsSliderPreference(
                title = stringResource(R.string.prefetch_limit),
                summary = stringResource(R.string.prefetch_limit_summary),
                value = settings.observeAsState(AppSettings.KEY_READER_PREFETCH_LIMIT) { readerPrefetchLimit }.value,
                valueRange = 1..20,
                step = 1,
                valueText = { it.toString() },
                onValueChange = { settings.prefs.edit { putInt(AppSettings.KEY_READER_PREFETCH_LIMIT, it) } }
            )
            }
        }
    }
}
