package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.reader.core.ZoomMode
import kotlin.math.roundToInt

/**
 * Android's reader settings screen, which the Windows host only offered inside an open chapter: the same preferences
 * existed, but a reader had to be open to reach them, and the panel mixed them with super-resolution downloads and the
 * tap grid. The page shows the reading preferences on their own, in two sections — the page itself, then page turning —
 * with every value in a dialog exactly like the other Windows settings pages.
 *
 * It edits the same [DesktopReaderSettings] the reader panel does (same preference keys), so a change made here is what
 * the next opened chapter uses.
 */
@Composable
internal fun DesktopReaderSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.readerSettings
    val active = !state.busy
    val continuous = settings.mode == DesktopReaderMode.CONTINUOUS
    var dialog by remember { mutableStateOf<ReaderSettingsDialog?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active, modifier = Modifier.testTag("reader-settings-back")) {
            Text(desktopText("common.back"))
        }
        SettingsSectionHeader(desktopText("reader.settingsPages"))
        SettingsGroup {
            SettingsValueRow(desktopText("reader.setting.mode"), null, modeTitle(settings.mode), active,
                { dialog = ReaderSettingsDialog.MODE }, testTag = "reader-setting-mode")
            SettingsRowDivider()
            SettingsValueRow(desktopText("reader.setting.direction"), null, settings.directionTitle, active,
                { dialog = ReaderSettingsDialog.DIRECTION }, testTag = "reader-setting-direction")
            SettingsRowDivider()
            SettingsValueRow(desktopText("reader.setting.fit"), null, fitTitle(settings.fitMode), active,
                { dialog = ReaderSettingsDialog.FIT }, testTag = "reader-setting-fit")
            SettingsRowDivider()
            SettingsValueRow(desktopText("reader.setting.background"), null, settings.background.title, active,
                { dialog = ReaderSettingsDialog.BACKGROUND }, testTag = "reader-setting-background")
            SettingsRowDivider()
            SettingsSwitchRow(desktopText("reader.setting.pageNumbers"), null, settings.pageNumbers, active,
                { controller.readerSettings(settings.copy(pageNumbers = it)) },
                testTag = "reader-setting-page-numbers")
            SettingsRowDivider()
            // Android keeps one "crop pages" switch per reader, so the page shows the one the current mode uses.
            SettingsSwitchRow(desktopText("reader.setting.crop"), null, settings.cropActive, active,
                { crop ->
                    controller.readerSettings(if (continuous) settings.copy(cropContinuous = crop)
                    else settings.copy(cropPaged = crop))
                }, testTag = "reader-setting-crop")
            if (!continuous) {
                SettingsRowDivider()
                SettingsSwitchRow(desktopText("reader.setting.doubleCover"), null, settings.doublePageCover, active,
                    { controller.readerSettings(settings.copy(doublePageCover = it)) },
                    testTag = "reader-setting-double-cover")
            }
        }
        SettingsSectionHeader(desktopText("reader.settingsTurning"))
        SettingsGroup {
            SettingsSwitchRow(desktopText("reader.autoAcrossChapters"), null, settings.automaticChapter, active,
                { controller.readerSettings(settings.copy(automaticChapter = it)) },
                testTag = "reader-setting-auto-chapter")
            SettingsRowDivider()
            // A continuous reader has no page turns, so Android greys the animation out there too.
            SettingsValueRow(desktopText("reader.setting.animation"), null, animationTitle(settings.animation),
                active && !continuous, { dialog = ReaderSettingsDialog.ANIMATION }, testTag = "reader-setting-animation")
            SettingsRowDivider()
            ReaderSpeedRow(controller, settings, active)
        }
        // Android keeps the novel section (replacement rules, dictionary rules, …) on the novel half of this screen.
        SettingsSectionHeader(AndroidStrings["novel"])
        SettingsGroup {
            SettingsValueRow(AndroidStrings["replace_rules"], AndroidStrings["replace_rules_summary"], null, active,
                { controller.showReplaceRules() }, testTag = "reader-setting-replace-rules")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["dictionary_rules"], AndroidStrings["dictionary_rules_summary"], null,
                active, { controller.showDictionaryRules() }, testTag = "reader-setting-dictionary-rules")
        }
    }
    val open = dialog ?: return
    val enabled = active
    when (open) {
        ReaderSettingsDialog.MODE -> DesktopChoiceSettingsDialog(
            title = desktopText("reader.setting.mode"), summary = null,
            bodyTag = "reader-setting-dialog", optionTagPrefix = "reader-mode",
            options = DesktopReaderMode.entries.map { modeTitle(it) to it.name },
            selected = settings.mode.name, enabled = enabled,
            onPick = { value -> controller.readerSettings(settings.copy(mode = DesktopReaderMode.valueOf(value))) },
            onDismiss = { dialog = null },
        )
        ReaderSettingsDialog.DIRECTION -> DesktopChoiceSettingsDialog(
            title = desktopText("reader.setting.direction"), summary = null,
            bodyTag = "reader-direction-dialog", optionTagPrefix = "reader-direction",
            options = listOf(
                desktopText("reader.direction.ltr") to "LTR",
                desktopText("reader.direction.rtl") to "RTL",
                desktopText("reader.direction.vertical") to "VERTICAL",
            ),
            selected = settings.directionName, enabled = enabled,
            onPick = { value ->
                // Vertical wins over right-to-left in the shared scene, so only one of the two flags is set.
                controller.readerSettings(when (value) {
                    "RTL" -> settings.copy(rightToLeft = true, vertical = false)
                    "VERTICAL" -> settings.copy(rightToLeft = false, vertical = true)
                    else -> settings.copy(rightToLeft = false, vertical = false)
                })
            },
            onDismiss = { dialog = null },
        )
        ReaderSettingsDialog.FIT -> DesktopChoiceSettingsDialog(
            title = desktopText("reader.setting.fit"), summary = null,
            bodyTag = "reader-fit-dialog", optionTagPrefix = "reader-fit",
            options = ZoomMode.entries.map { fitTitle(it) to it.name },
            selected = settings.fitMode.name, enabled = enabled,
            onPick = { value -> controller.readerSettings(settings.copy(fitMode = ZoomMode.valueOf(value))) },
            onDismiss = { dialog = null },
        )
        ReaderSettingsDialog.BACKGROUND -> DesktopChoiceSettingsDialog(
            title = desktopText("reader.setting.background"), summary = null,
            bodyTag = "reader-background-dialog", optionTagPrefix = "reader-background",
            options = DesktopReaderBackground.entries.map { it.title to it.name },
            selected = settings.background.name, enabled = enabled,
            onPick = { value ->
                controller.readerSettings(settings.copy(background = DesktopReaderBackground.valueOf(value)))
            },
            onDismiss = { dialog = null },
        )
        ReaderSettingsDialog.ANIMATION -> DesktopChoiceSettingsDialog(
            title = desktopText("reader.setting.animation"), summary = null,
            bodyTag = "reader-animation-dialog", optionTagPrefix = "reader-animation",
            options = ReaderAnimation.entries.map { animationTitle(it) to it.name },
            selected = settings.animation.name, enabled = enabled,
            onPick = { value -> controller.readerSettings(settings.copy(animation = ReaderAnimation.valueOf(value))) },
            onDismiss = { dialog = null },
        )
    }
}

/** Which settings row's dialog is open; each row owns exactly one choice list. */
private enum class ReaderSettingsDialog { MODE, DIRECTION, FIT, BACKGROUND, ANIMATION }

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

/**
 * Android's auto page-turn / scroll speed slider. It commits on release instead of on every drag pixel, which is what
 * the reader panel does as well and keeps the preference store from writing dozens of values per gesture.
 */
@Composable
private fun ReaderSpeedRow(controller: DesktopController, settings: DesktopReaderSettings, enabled: Boolean) {
    var draft by remember(settings.autoScrollSpeed) { mutableStateOf(settings.autoScrollSpeed) }
    SettingsActionRow(desktopText("reader.autoScrollSpeed"), null, Modifier.testTag("reader-setting-speed")) {
        Slider(value = draft, onValueChange = { draft = it }, valueRange = 0f..1f, enabled = enabled,
            onValueChangeFinished = { controller.readerSettings(settings.copy(autoScrollSpeed = draft)) },
            modifier = Modifier.width(220.dp).testTag("reader-setting-speed-slider"))
        Text("${(draft * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(48.dp))
    }
}
