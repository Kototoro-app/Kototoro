package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** The host provides the supported kinds and the policy keys it already persists. */
data class ExtensionInstallBehaviorUiItem(
    val type: String,
    val title: String,
    val policy: String,
    val icon: Painter? = null,
)

/** Shared batch/per-kind editor; the sheet or dialog surface and policy writes remain in the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionInstallBehaviorContent(
    behaviors: List<ExtensionInstallBehaviorUiItem>,
    policyOptions: List<PreferenceChoice<String>>,
    onPolicyChange: (String, String) -> Unit,
    onBatchSetPolicy: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showTitle: Boolean = true,
    topContent: @Composable ColumnScope.() -> Unit = {},
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    val uniformPolicy = behaviors.map { it.policy }.distinct().singleOrNull()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("install-behavior-body"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        topContent()
        if (showTitle) Text(uiString("extension_install_behavior"), style = MaterialTheme.typography.titleLarge)
        Text(uiString("extension_install_behavior_sheet_summary"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            SettingsSectionHeader(uiString("extension_install_behavior_batch_title"))
            Column(Modifier.fillMaxWidth().testTag("install-behavior-batch")) {
                policyOptions.forEach { option ->
                    val active = enabled && behaviors.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth().testTag("install-behavior-batch:" + option.value)
                            .tvFocusable(enabled = active, addFocusTarget = false)
                            .selectable(uniformPolicy == option.value, enabled = active, role = Role.RadioButton,
                                onClick = { onBatchSetPolicy(option.value) })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(uniformPolicy == option.value, onClick = null, enabled = active)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Column {
            SettingsSectionHeader(uiString("extension_install_behavior_per_source"))
            SettingsGroup {
                behaviors.forEachIndexed { index, item ->
                    if (index > 0) SettingsRowDivider()
                    SettingsValueRow(item.title, null,
                        policyOptions.firstOrNull { it.value == item.policy }?.label, enabled,
                        { choosing = item.type }, testTag = "install-behavior-row:" + item.type,
                        leadingIcon = item.icon,
                        modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                }
            }
        }
    }
    val item = behaviors.firstOrNull { it.type == choosing } ?: return
    PreferenceChoiceDialog(
        title = item.title + " · " + uiString("extension_install_behavior"),
        selected = item.policy,
        options = policyOptions,
        onSelect = { onPolicyChange(item.type, it) },
        onDismiss = { choosing = null },
        enabled = enabled,
        tagPrefix = "install-behavior-policy",
        renderDialog = renderDialog,
    )
}
