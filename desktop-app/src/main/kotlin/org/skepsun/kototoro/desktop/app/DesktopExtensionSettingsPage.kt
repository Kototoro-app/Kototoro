package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.extensions.resolveJarPriorityOrder
import org.skepsun.kototoro.core.ui.compose.ReorderEditorDialog
import org.skepsun.kototoro.core.ui.compose.ReorderItem
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.core.ui.settings.ExtensionSettingsContent

/**
 * Android's extension settings page, reached from 扩展与仓库: the plugin de-duplication priority and what installing an
 * extension does to its sources. Both moved here from the source settings, which now hold only browsing preferences.
 * The broken-sources switch is gone as on Android: a parser marked `@Broken` is switched off when it first appears and
 * the user switches it on in the source list.
 */
@Composable
internal fun DesktopExtensionSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val active = !state.busy
    var dialog by remember { mutableStateOf<ExtensionSettingsDialog?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExtensionSettingsContent(
            installBehaviorSummary = state.extensionInstallPolicies.policySummary(),
            onInstallBehaviorClick = { dialog = ExtensionSettingsDialog.INSTALL_BEHAVIOR },
            jarPriorityEditor = {
                SettingsValueRow(AndroidStrings["jar_priority_order_title"],
                    AndroidStrings["jar_priority_order_summary"],
                    state.jarPriorityOrder.resolvedPluginNames(state).takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: AndroidStrings["not_specified"],
                    active, { dialog = ExtensionSettingsDialog.JAR_PRIORITY }, testTag = "source-setting-jar-priority")
                SettingsRowDivider()
                SettingsInfoRow(desktopText("sources.jarPriorityNote"), null)
            },
            enabled = active,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = LocalDesktopTopInset.current),
        )
    }
    when (dialog) {
        ExtensionSettingsDialog.JAR_PRIORITY -> DesktopJarPriorityDialog(state = state, enabled = active,
            onOrder = { controller.setJarPriorityOrder(it) }, onDismiss = { dialog = null })
        ExtensionSettingsDialog.INSTALL_BEHAVIOR -> DesktopInstallBehaviorDialog(
            policies = state.extensionInstallPolicies,
            enabled = active,
            onPolicy = { typeName, policy -> controller.setExtensionInstallPolicy(typeName, policy) },
            onAllPolicies = { controller.setAllExtensionInstallPolicies(it) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class ExtensionSettingsDialog { JAR_PRIORITY, INSTALL_BEHAVIOR }

/**
 * The parser plugins that `jar_priority_order` ranks, in that order; plugins the value does not name come last. A
 * session that has not listed its extensions yet only knows the stored names, which are the plugin ids anyway.
 */
internal fun String.resolvedPluginNames(state: DesktopAppState): List<String> {
    val installed = state.installedEntries.filter { it.kind == DesktopInstalledKind.PARSER }.map { it.id }
    return if (installed.isEmpty()) parseJarPriorityNames() else resolveJarPriorityOrder(installed, this)
}

/**
 * Android's reorder preference for the deduplication order. The list is the installed parser plugins; each move
 * stores immediately like the category manager does, because [org.skepsun.kototoro.parserhost.ParserPluginRegistry]
 * applies the order when it opens — the row's note says so instead of promising a live re-rank.
 */
@Composable
private fun DesktopJarPriorityDialog(state: DesktopAppState, enabled: Boolean, onOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit) {
    val order = state.jarPriorityOrder.resolvedPluginNames(state)
    ReorderEditorDialog(
        items = order.map { ReorderItem(it, it) },
        onDismissRequest = onDismiss,
        onSave = { onOrder(it) },
        enabled = enabled,
        saveOnMove = true,
        title = AndroidStrings["jar_priority_order_title"],
        summary = AndroidStrings["jar_priority_order_summary"],
        footerText = desktopText("sources.jarPriorityNote"),
        emptyText = desktopText("extensions.installedEmpty"),
        tagPrefix = "jar-priority",
    )
}

