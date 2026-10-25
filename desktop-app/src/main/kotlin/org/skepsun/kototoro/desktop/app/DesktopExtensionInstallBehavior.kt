package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy

/**
 * Android's `UnifiedInstallChoiceDialog`: the question a package install asks while the kind is set to
 * `ASK_EVERY_TIME`. Android offers "install and enable" as the confirming action, "install only" next to cancel, and
 * the checkbox that stops the question for that kind; this dialog keeps that arrangement and the same strings.
 */
@Composable
internal fun DesktopInstallPromptDialog(
    request: DesktopInstallRequest,
    onChoose: (enableSources: Boolean, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var remember by remember(request) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("install-prompt-dialog"),
        title = { Text(AndroidStrings.format("extension_install_choice_title", request.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (request.sourceCount > 0) {
                        AndroidStrings.format("extension_install_choice_message", request.sourceCount)
                    } else {
                        AndroidStrings["extension_install_choice_message_unknown"]
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { remember = !remember }) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it },
                        modifier = Modifier.testTag("install-prompt-remember"))
                    Text(
                        AndroidStrings.format("extension_install_remember_choice",
                            AndroidStrings[request.kind.titleKey]),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button({ onChoose(true, remember) }, modifier = Modifier.testTag("install-prompt-enable")) {
                Text(AndroidStrings["extension_install_and_enable"])
            }
        },
        dismissButton = {
            Row {
                TextButton({ onDismiss() }, modifier = Modifier.testTag("install-prompt-cancel")) {
                    Text(desktopText("common.cancel"))
                }
                TextButton({ onChoose(false, remember) }, modifier = Modifier.testTag("install-prompt-only")) {
                    Text(AndroidStrings["extension_install_only"])
                }
            }
        },
    )
}

/**
 * Android's `ExtensionInstallBehaviorSheet`. The sheet is a list of rows there because a phone has one column and a
 * bottom sheet; this is the same list in a dialog: the batch answer (Android's segmented "apply to all sources" row),
 * then one row per kind this host installs, each opening [DesktopChoiceSettingsDialog] with the three policies.
 */
@Composable
internal fun DesktopInstallBehaviorDialog(
    policies: Map<String, ExtensionInstallPolicy>,
    enabled: Boolean,
    onPolicy: (typeName: String, policy: ExtensionInstallPolicy) -> Unit,
    onAllPolicies: (policy: ExtensionInstallPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = the sheet, [BATCH_ROW] = the shared answer, otherwise the kind whose row was clicked.
    var choosing by remember { mutableStateOf<String?>(null) }
    val options = ExtensionInstallPolicy.entries.map { AndroidStrings[it.labelKey()] to it.name }
    val choseKind = choosing?.takeIf { it != BATCH_ROW }
    // A batch row highlights only when every kind already agrees, like Android's segmented buttons.
    val uniform = DesktopInstallKind.entries.map { policies.policyFor(it) }.distinct().singleOrNull()
    if (choosing != null) {
        val kind = DesktopInstallKind.entries.firstOrNull { it.typeName == choseKind }
        DesktopChoiceSettingsDialog(
            title = kind?.let { "${AndroidStrings[it.titleKey]} · ${AndroidStrings["extension_install_behavior"]}" }
                ?: AndroidStrings["extension_install_behavior_batch_title"],
            summary = AndroidStrings["extension_install_behavior_sheet_summary"],
            bodyTag = "install-behavior-choice",
            optionTagPrefix = "install-behavior-option",
            options = options,
            selected = if (kind != null) policies.policyFor(kind).name else uniform?.name,
            enabled = enabled,
            onPick = { name ->
                val policy = ExtensionInstallPolicy.entries.first { it.name == name }
                if (kind != null) onPolicy(kind.typeName, policy) else onAllPolicies(policy)
            },
            onDismiss = { choosing = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("install-behavior-dialog"),
        title = { Text(AndroidStrings["extension_install_behavior"]) },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .testTag("install-behavior-body")) {
                Text(AndroidStrings["extension_install_behavior_sheet_summary"],
                    style = MaterialTheme.typography.bodySmall)
                SettingsValueRow(
                    AndroidStrings["extension_install_behavior_batch_title"], null, policies.policySummary(), enabled,
                    { choosing = BATCH_ROW }, testTag = "install-behavior-batch",
                )
                SettingsSectionHeader(AndroidStrings["extension_install_behavior_per_source"])
                SettingsGroup {
                    DesktopInstallKind.entries.forEachIndexed { index, kind ->
                        if (index > 0) SettingsRowDivider()
                        SettingsValueRow(
                            AndroidStrings[kind.titleKey], null,
                            AndroidStrings[policies.policyFor(kind).labelKey()], enabled,
                            { choosing = kind.typeName }, testTag = "install-behavior-row:${kind.typeName}",
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("install-behavior-close")) {
                Text(desktopText("common.close"))
            }
        },
    )
}

/** The batch row's placeholder in [DesktopInstallBehaviorDialog]'s click state; no kind is named like this. */
private const val BATCH_ROW = ""
