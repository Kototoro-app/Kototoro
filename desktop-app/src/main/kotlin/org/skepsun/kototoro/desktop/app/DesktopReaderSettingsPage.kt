package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.settings.NovelRuleSettingsRows
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.core.ui.settings.ReaderCropSetting
import org.skepsun.kototoro.core.ui.settings.ReaderWebtoonSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderPreloadSettingsRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderAutoScrollSpeedControl
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsUiState
import org.skepsun.kototoro.core.ui.settings.ReaderZoomSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderZoomSettingsUiState
import org.skepsun.kototoro.core.ui.settings.ReaderModeSettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderModeSettingsUiState
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.reader.core.ZoomMode

/**
 * Desktop host for shared reader setting groups and speed control. Direction and automatic chapter switching
 * remain here; the crop adapter only writes the preference for the current reader mode.
 * Edits use the same [DesktopReaderSettings] and preference keys as the open reader panel.
 */
@Composable
internal fun DesktopReaderSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.readerSettings
    val active = !state.busy
    val continuous = settings.mode.isContinuous
    // Android's "gaps in webtoon mode" only has a meaning where pages stack vertically.
    val webtoon = settings.mode.isVerticalStrip
    var directionDialogVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReaderModeSettingsContent(
            state = ReaderModeSettingsUiState(settings.mode.name, settings.readerModeDetection, settings.background.name),
            modeOptions = DesktopReaderMode.entries.map { PreferenceChoice(it.name, modeTitle(it)) },
            backgroundOptions = DesktopReaderBackground.entries.map { PreferenceChoice(it.name, it.title) },
            onModeChange = { controller.readerSettings(settings.copy(mode = DesktopReaderMode.valueOf(it))) },
            onDetectModeChange = { controller.readerSettings(settings.copy(readerModeDetection = it)) },
            onBackgroundChange = {
                controller.readerSettings(settings.copy(background = DesktopReaderBackground.valueOf(it)))
            },
            enabled = active,
            title = desktopText("reader.settingsPages"),
        )
        ReaderZoomSettingsContent(
            state = ReaderZoomSettingsUiState(settings.fitMode.name, settings.animation.name, !continuous),
            fitOptions = ZoomMode.entries.map { PreferenceChoice(it.name, fitTitle(it)) },
            animationOptions = ReaderAnimation.entries.map { PreferenceChoice(it.name, animationTitle(it)) },
            crop = ReaderCropSetting.CurrentMode(settings.cropActive) { crop ->
                controller.readerSettings(if (continuous) settings.copy(cropContinuous = crop)
                    else settings.copy(cropPaged = crop))
            },
            onFitChange = { controller.readerSettings(settings.copy(fitMode = ZoomMode.valueOf(it))) },
            onAnimationChange = { controller.readerSettings(settings.copy(animation = ReaderAnimation.valueOf(it))) },
            enabled = active,
        )
        ReaderDisplaySettingsContent(
            state = ReaderDisplaySettingsUiState(settings.chapterTitleAtBottom, settings.pageNumbers),
            onChapterTitleAtBottomChange = { controller.readerSettings(settings.copy(chapterTitleAtBottom = it)) },
            onPageNumbersChange = { controller.readerSettings(settings.copy(pageNumbers = it)) },
            enabled = active,
            // Desktop centers the page number; Android's summary describes its bottom-corner overlay.
            pageNumbersSummary = null,
        )
        SettingsGroup {
            SettingsValueRow(desktopText("reader.setting.direction"), null, settings.directionTitle, active,
                { directionDialogVisible = true }, testTag = "reader-setting-direction")
            if (!continuous) {
                SettingsRowDivider()
                SettingsSwitchRow(desktopText("reader.setting.doubleCover"), null, settings.doublePageCover, active,
                    { controller.readerSettings(settings.copy(doublePageCover = it)) },
                    testTag = "reader-setting-double-cover")
            }
            SettingsRowDivider()
            ReaderPreloadSettingsRow(settings.reducePagePreloading,
                { controller.readerSettings(settings.copy(reducePagePreloading = it)) }, enabled = active)
        }
        if (webtoon) {
            ReaderWebtoonSettingsContent(settings.webtoonGaps,
                { controller.readerSettings(settings.copy(webtoonGaps = it)) }, enabled = active)
        }
        SettingsSectionHeader(desktopText("reader.settingsTurning"))
        SettingsGroup {
            ReaderAutoScrollSpeedControl(settings.autoScrollSpeed,
                { controller.readerSettings(settings.copy(autoScrollSpeed = it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                enabled = active, title = desktopText("reader.autoScrollSpeed"),
                sliderTag = "reader-setting-speed-slider")
        }
        // Android keeps the novel section (replacement rules, dictionary rules, …) on the novel half of this screen.
        SettingsSectionHeader(AndroidStrings["novel"])
        SettingsGroup {
            NovelRuleSettingsRows(
                onReplaceRulesClick = { controller.showReplaceRules() },
                onDictionaryRulesClick = { controller.showDictionaryRules() },
                enabled = active,
            )
        }
    }
    if (!directionDialogVisible) return
    DesktopChoiceSettingsDialog(
        title = desktopText("reader.setting.direction"), summary = null,
        bodyTag = "reader-direction-dialog", optionTagPrefix = "reader-direction",
        options = listOf(
            desktopText("reader.direction.ltr") to "LTR",
            desktopText("reader.direction.rtl") to "RTL",
            desktopText("reader.direction.vertical") to "VERTICAL",
        ),
        selected = settings.directionName, enabled = active,
        onPick = { value ->
            // Vertical wins over right-to-left in the shared scene, so only one of the two flags is set.
            controller.readerSettings(when (value) {
                "RTL" -> settings.copy(rightToLeft = true, vertical = false)
                "VERTICAL" -> settings.copy(rightToLeft = false, vertical = true)
                else -> settings.copy(rightToLeft = false, vertical = false)
            })
        },
        onDismiss = { directionDialogVisible = false },
    )
}

/** The direction row's current value, as the choice dialog names it. */
private val DesktopReaderSettings.directionName: String
    get() = when {
        vertical -> "VERTICAL"
        rightToLeft -> "RTL"
        else -> "LTR"
    }

/** Spelled out instead of built from [directionName]: the string verifier only sees literal keys. */
private val DesktopReaderSettings.directionTitle: String
    get() = when (directionName) {
        "VERTICAL" -> desktopText("reader.direction.vertical")
        "RTL" -> desktopText("reader.direction.rtl")
        else -> desktopText("reader.direction.ltr")
    }
