package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallPromptDialog
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallBehaviorContent
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallBehaviorUiItem
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice

/** The host retains request-scoped checkbox state and delegates the full prompt to common UI. */
@Composable
internal fun DesktopInstallPromptDialog(
    request: DesktopInstallRequest,
    onChoose: (enableSources: Boolean, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var remember by remember(request) { mutableStateOf(false) }
    ExtensionInstallPromptDialog(
        name = request.name,
        sourceCount = request.sourceCount,
        kindLabel = AndroidStrings[request.kind.titleKey],
        rememberChoice = remember,
        onRememberChange = { remember = it },
        onChoose = { onChoose(it, remember) },
        onDismiss = onDismiss,
    )
}

/** Desktop owns the surface; the batch and per-kind editor is shared with Android. */
@Composable
internal fun DesktopInstallBehaviorDialog(
    policies: Map<String, ExtensionInstallPolicy>,
    enabled: Boolean,
    onPolicy: (typeName: String, policy: ExtensionInstallPolicy) -> Unit,
    onAllPolicies: (policy: ExtensionInstallPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("install-behavior-dialog"),
        title = { Text(AndroidStrings["extension_install_behavior"]) },
        text = {
            ExtensionInstallBehaviorContent(
                behaviors = DesktopInstallKind.entries.map {
                    ExtensionInstallBehaviorUiItem(it.typeName, AndroidStrings[it.titleKey], policies.policyFor(it).name)
                },
                policyOptions = ExtensionInstallPolicy.entries.map { PreferenceChoice(it.name, AndroidStrings[it.labelKey()]) },
                onPolicyChange = { type, policy -> onPolicy(type, ExtensionInstallPolicy.valueOf(policy)) },
                onBatchSetPolicy = { onAllPolicies(ExtensionInstallPolicy.valueOf(it)) },
                enabled = enabled,
                showTitle = false,
                modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 520.dp),
            )
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("install-behavior-close")) {
                Text(desktopText("common.close"))
            }
        },
    )
}
