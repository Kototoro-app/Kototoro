package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.buttons.OutlinedButton
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.core.ui.wizard.WizardDonePage
import org.skepsun.kototoro.core.ui.wizard.WizardStartPage
import org.skepsun.kototoro.core.ui.wizard.WizardPageHeader
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/**
 * Shares the content-first welcome page with Android. Desktop supplies source and backup navigation;
 * appearance remains optional and Android-only local import and permissions are not offered here.
 * This screen remains a manual entry from source settings.
 */
@Composable
internal fun DesktopSetupWizard(controller: DesktopController, state: DesktopAppState) {
    var step by remember { mutableStateOf(WizardStep.INTRO) }
    var dialog by remember { mutableStateOf<WizardDialog?>(null) }
    val active = !state.busy
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = LocalDesktopTopInset.current),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.showSourceSettings() }, enabled = active) { Text(desktopText("common.back")) }
        when (step) {
            WizardStep.INTRO -> WizardStartPage(
                step = 1,
                totalSteps = WizardStep.entries.size,
                onAddSources = { controller.showExtensions() },
                onRestoreBackup = { controller.showBackups() },
                onStart = { controller.library(false) },
                enabled = active,
            )
            WizardStep.APPEARANCE -> WizardAppearance(state, active) { dialog = it }
            WizardStep.DONE -> WizardDonePage(
                step = step.ordinal + 1,
                totalSteps = WizardStep.entries.size,
                onOpenExtensionManagement = { controller.showExtensions() },
                onRestoreBackup = { controller.showBackups() },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { step = WizardStep.entries[step.ordinal - 1] },
                enabled = active && step.ordinal > 0,
                modifier = Modifier.testTag("wizard-back"),
            ) {
                // Android's wizard action bar draws a bare back arrow; the text keeps it apart from the page-level
                // 返回 above, which leaves the wizard instead of stepping backwards.
                Text(desktopText("common.previous"))
            }
            Button(
                onClick = {
                    if (step == WizardStep.DONE) controller.showSourceSettings() else step = step.next()
                },
                enabled = active,
                colors = if (step == WizardStep.INTRO) {
                    androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                },
                modifier = Modifier.testTag("wizard-next"),
            ) {
                // Android swaps its "next" label for "done" on the last page.
                Text(AndroidStrings[when (step) {
                    WizardStep.INTRO -> "welcome_continue_setup"
                    WizardStep.DONE -> "done"
                    else -> "next"
                }])
            }
        }
    }
    when (dialog) {
        WizardDialog.THEME -> DesktopChoiceSettingsDialog(
            title = desktopText("more.theme"),
            summary = null,
            bodyTag = "wizard-theme-dialog",
            optionTagPrefix = "wizard-theme",
            options = DesktopAppearance.entries.map { it.title to it.name },
            selected = state.appearance.name,
            enabled = active,
            onPick = {
                controller.appearance(DesktopAppearance.valueOf(it))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        WizardDialog.INTERFACE_STYLE -> DesktopChoiceSettingsDialog(
            title = desktopText("more.interfaceStyle"),
            summary = null,
            bodyTag = "wizard-interface-style-dialog",
            optionTagPrefix = "wizard-interface-style",
            options = DesktopInterfaceStyle.entries.map { it.title to it.name },
            selected = state.interfaceStyle.name,
            enabled = active,
            onPick = {
                controller.interfaceStyle(DesktopInterfaceStyle.valueOf(it))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        WizardDialog.LANGUAGE -> DesktopChoiceSettingsDialog(
            title = desktopText("more.language"),
            summary = desktopText("more.languageDetail"),
            bodyTag = "wizard-language-dialog",
            optionTagPrefix = "wizard-language",
            options = listOf(desktopText("more.languageSystem") to "system") +
                DesktopLanguages.bundled.map { it.nativeName to it.tag },
            selected = state.language ?: "system",
            enabled = active,
            onPick = { tag ->
                controller.language(tag.takeUnless { it == "system" })
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Android's `WelcomeAppearanceStep`, minus the Android-only transition/panorama switches, plus 主题 and 语言. */
@Composable
private fun WizardAppearance(
    state: DesktopAppState,
    active: Boolean,
    open: (WizardDialog) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WizardPageHeader(
            step = WizardStep.APPEARANCE.ordinal + 1,
            totalSteps = WizardStep.entries.size,
            title = uiString("welcome_appearance_title"),
            // Android's `welcome_appearance_summary` promises the navigation transition and the details panorama, which
            // only the phone draws, so the desktop page says what it actually offers.
            summary = desktopText("sources.wizardAppearanceSummary"),
            iconVector = ReaderPanelIcons.Palette,
        )
        SettingsGroup {
            SettingsValueRow(desktopText("more.theme"), null, state.appearance.title, active,
                { open(WizardDialog.THEME) }, testTag = "wizard-theme")
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.interfaceStyle"), null, state.interfaceStyle.title, active,
                { open(WizardDialog.INTERFACE_STYLE) }, testTag = "wizard-interface-style")
            SettingsRowDivider()
            SettingsValueRow(desktopText("more.language"), desktopText("more.languageDetail"),
                state.language?.let { tag -> DesktopLanguages.bundled.firstOrNull { it.tag == tag }?.nativeName ?: tag }
                    ?: desktopText("more.languageSystem"),
                active, { open(WizardDialog.LANGUAGE) }, testTag = "wizard-language")
        }
    }
}

private enum class WizardStep {
    INTRO,
    APPEARANCE,
    DONE,
    ;

    fun next(): WizardStep = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

private enum class WizardDialog { THEME, INTERFACE_STYLE, LANGUAGE }
