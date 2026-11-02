package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.core.ui.wizard.WizardDonePage
import org.skepsun.kototoro.core.ui.wizard.WizardIntroPage
import org.skepsun.kototoro.core.ui.wizard.WizardPageHeader
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/**
 * Android's setup wizard, which Windows only had as a missing row at the bottom of 来源设置. Android's flow is four
 * pager pages — intro, permissions, appearance, done — so it arrives here as the three pages a desktop host can honour:
 *
 * - the intro page and the done page are `core-ui`'s shared `WizardIntroPage`/`WizardDonePage`, which is Android's own
 *   page bodies (the `welcome_intro_*`/`welcome_done_*` strings, the concept cards, the disclaimer, the two jumps), so
 *   the phone and the desktop read the same page instead of two translations of it;
 * - the appearance page keeps Android's `interface_style` idea and adds the two settings Windows has instead
 *   (主题/语言), each opening the same choice dialog 更多 uses, because a desktop wizard that could only pick a
 *   style would leave the two most visible settings untouched. Android's `WelcomeAppearanceStep` also carries the
 *   navigation-transition and details-panorama switches, which only the phone draws, so that page stays host-side;
 * - Android's permissions page is dropped: Windows grants nothing at runtime, and every row on that page
 *   (notifications, install-packages, battery optimisation) is an Android-only capability.
 *
 * Android's spaces page is dropped too — Windows has one library and no spaces — and the wizard does **not** open by
 * itself on a fresh data root the way Android's first start does: a Windows probe (and a user who just wants to look
 * around) must not land inside a modal flow, so it stays a manual entry from 来源设置.
 *
 * The step counter the pages used to print above themselves now comes from the shared header, so the three pages read
 * "Step N of M" exactly like the phone's `WizardPageHeader`.
 */
@Composable
internal fun DesktopSetupWizard(controller: DesktopController, state: DesktopAppState) {
    var step by remember { mutableStateOf(WizardStep.INTRO) }
    var dialog by remember { mutableStateOf<WizardDialog?>(null) }
    val active = !state.busy
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.showSourceSettings() }, enabled = active) { Text(desktopText("common.back")) }
        when (step) {
            WizardStep.INTRO -> WizardIntroPage(step = step.ordinal + 1, totalSteps = WizardStep.entries.size)
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
                modifier = Modifier.testTag("wizard-next"),
            ) {
                // Android swaps its "next" label for "done" on the last page.
                Text(AndroidStrings[if (step == WizardStep.DONE) "done" else "next"])
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
