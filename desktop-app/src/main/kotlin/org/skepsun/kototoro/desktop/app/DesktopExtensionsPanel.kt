package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow

/**
 * Extension management, drawn with the same settings vocabulary as the Android screens: one labelled card per
 * section, rows separated by a hairline, and a control on the row instead of loose widgets stacked on the page.
 *
 * Android's "扩展管理" is a tabbed unified screen (repositories, JSON sources, APK extensions, JAR parsers). The Windows
 * host covers the repository part, the JAR/APK extensions that come from it, and the imported JSON sources it can
 * serve (TVBox / Apple CMS links, added from this page and routed to the shared CMS runtime).
 */
@Composable
internal fun DesktopExtensionsPanel(controller: DesktopController, closing: Boolean) {
    val state by controller.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val enabled = !state.busy && !closing
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(desktopText("extensions.repositories"))
        SettingsGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = desktopText("extensions.repositoryDetail"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(desktopText("extensions.repositoryAddress")) },
                        placeholder = { Text("https://example.com/index.pb") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag("repository-url"),
                    )
                    Button(
                        onClick = { controller.fetchRepository(address) },
                        enabled = enabled && address.isNotBlank(),
                        modifier = Modifier.testTag("repository-add"),
                    ) { Text(desktopText("extensions.addRepository")) }
                }
            }
            state.repositories.forEach { repo ->
                SettingsRowDivider()
                SettingsActionRow(
                    title = repo.name,
                    summary = repo.indexUrl,
                    verticalPadding = 6.dp,
                ) {
                    TextButton(
                        onClick = { address = repo.indexUrl; controller.fetchRepository(repo.indexUrl) },
                        enabled = enabled,
                        modifier = Modifier.testTag("repository:${repo.indexUrl}"),
                    ) { Text(desktopText("common.refresh"), maxLines = 1) }
                    TextButton(
                        onClick = { controller.removeRepository(repo) },
                        enabled = enabled,
                        modifier = Modifier.testTag("repository-remove:${repo.indexUrl}"),
                    ) { Text(desktopText("extensions.removeRepository"), maxLines = 1) }
                }
            }
        }
        // Android's unified screen also hosts the imported JSON sources; Windows serves the TVBox/Apple CMS family.
        SettingsSectionHeader(desktopText("jsonSources.title"))
        SettingsGroup {
            state.jsonSources.takeIf { it.isNotEmpty() }?.forEach { source ->
                SettingsActionRow(
                    title = source.name,
                    summary = source.api ?: source.id,
                    verticalPadding = 6.dp,
                ) {
                    TextButton(
                        onClick = { controller.removeJsonSource(source.id) },
                        enabled = enabled,
                        modifier = Modifier.testTag("json-source-remove:${source.id}"),
                    ) { Text(desktopText("jsonSources.remove"), maxLines = 1) }
                }
                SettingsRowDivider()
            }
            SettingsActionRow(
                title = desktopText("jsonSources.add"),
                summary = desktopText("jsonSources.addSummary"),
                verticalPadding = 6.dp,
            ) {
                TextButton(
                    onClick = { url = ""; title = ""; adding = true },
                    enabled = enabled,
                    modifier = Modifier.testTag("json-source-add"),
                ) { Text(desktopText("jsonSources.import")) }
            }
            SettingsRowDivider()
            SettingsInfoRow(desktopText("jsonSources.note"), null)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExtensionCatalog(controller, state, query, { query = it }, enabled, Modifier.weight(1f).fillMaxHeight())
            InstalledExtensions(controller, state, enabled, Modifier.width(380.dp).fillMaxHeight())
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            modifier = Modifier.testTag("json-source-dialog"),
            title = { Text(desktopText("jsonSources.add")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(desktopText("jsonSources.addDetail"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(desktopText("jsonSources.address")) },
                        placeholder = { Text(desktopText("jsonSources.addressHint")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("json-source-address"),
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(desktopText("jsonSources.name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("json-source-name"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { adding = false; controller.importJsonSource(url, title) },
                    modifier = Modifier.testTag("json-source-save"),
                ) { Text(desktopText("jsonSources.import")) }
            },
            dismissButton = {
                TextButton({ adding = false }, modifier = Modifier.testTag("json-source-cancel")) {
                    Text(desktopText("common.cancel"))
                }
            },
        )
    }
}

@Composable
private fun ExtensionCatalog(
    controller: DesktopController,
    state: DesktopAppState,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val catalog = state.extensionCatalog
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(
            catalog?.let { desktopText("extensions.catalogCount", it.repository.name, it.extensions.size) }
                ?: desktopText("extensions.catalog"),
        )
        SettingsGroup(Modifier.weight(1f)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(desktopText("extensions.search")) },
                singleLine = true,
                enabled = catalog != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("extension-search"),
            )
            SettingsRowDivider()
            val extensions = catalog?.extensions.orEmpty().filter { extension ->
                query.isBlank() || extension.name.contains(query, true) ||
                    extension.packageName.contains(query, true) ||
                    extension.sources.any { it.name.contains(query, true) || it.language.contains(query, true) }
            }
            if (extensions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsInfoRow(desktopText("extensions.catalogEmpty"), null)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("extension-list")) {
                    itemsIndexed(extensions, key = { _, extension -> extension.packageName }) { index, extension ->
                        if (index > 0) SettingsRowDivider()
                        val repository = requireNotNull(catalog)
                        val parserPlugin = repository.isParserPlugin(extension)
                        val cloudstreamPlugin = repository.isCloudstreamPlugin(extension)
                        val installed = state.installedExtensions[extension.packageName]
                        val parserInstalled = state.installedEntries.any {
                            it.kind == DesktopInstalledKind.PARSER && it.id == extension.packageName
                        }
                        val anime = !parserPlugin && extension.packageName.contains(".animeextension.")
                        val compatible = parserPlugin || cloudstreamPlugin || extension.extensionLib.matches(
                            // Mihon 1.4-1.6, Tsundoku 1.4/1.6 (checked again on install), Aniyomi generations 12-16.
                            if (anime) Regex("1[2-6](?:\\.\\d+)*") else Regex("1\\.[456](?:\\.\\d+)*"),
                        )
                        val hasPackage = extension.resources.jarUrl.isNotBlank() ||
                            extension.resources.apkUrl.isNotBlank()
                        val ecosystem = when {
                            cloudstreamPlugin -> "Cloudstream"
                            parserPlugin -> desktopText("extensions.kind.parser")
                            anime -> "Aniyomi"
                            extension.packageName.contains(".novelextension.") -> "Tsundoku"
                            else -> "Mihon"
                        }
                        val label = when {
                            !hasPackage -> desktopText("extensions.noPackage")
                            !compatible -> desktopText("extensions.unsupportedApi")
                            parserInstalled -> desktopText("extensions.installed")
                            installed != null && installed > extension.versionCode ->
                                desktopText("extensions.newerLocal")
                            installed == extension.versionCode -> desktopText("extensions.installed")
                            installed != null -> desktopText("extensions.update")
                            extension.resources.jarUrl.isBlank() && !parserPlugin && !cloudstreamPlugin ->
                                desktopText("extensions.installConvert")
                            else -> desktopText("extensions.install")
                        }
                        SettingsActionRow(
                            title = "${extension.name} · ${extension.versionName} · $ecosystem",
                            summary = extension.sources.takeIf { it.isNotEmpty() }
                                ?.joinToString { "${it.name} (${it.language})" }
                                ?: desktopText("extensions.sourcesAfterInstall"),
                            verticalPadding = 10.dp,
                        ) {
                            Button(
                                onClick = { controller.installExtension(extension) },
                                enabled = enabled && hasPackage && compatible && !parserInstalled &&
                                    (installed == null || installed < extension.versionCode),
                                modifier = Modifier.testTag("extension-install:${extension.packageName}"),
                            ) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

/** Installed extensions and plugins: updates from the saved repositories, automatic updates, and removal. */
@Composable
private fun InstalledExtensions(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<DesktopInstalledEntry?>(null) }
    val updates = state.extensionUpdates.associateBy { it.extension.packageName }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(desktopText("extensions.installedCount", state.installedEntries.size))
        SettingsGroup {
            SettingsSwitchRow(
                title = desktopText("extensions.autoUpdate"),
                summary = null,
                checked = state.autoUpdateExtensions,
                enabled = enabled,
                onCheckedChange = { controller.setAutoUpdateExtensions(it) },
                testTag = "extension-auto-update",
            )
            SettingsRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { controller.checkExtensionUpdates() },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("extension-check-updates"),
                ) { Text(desktopText("extensions.checkUpdates")) }
                Button(
                    onClick = { controller.applyUpdates() },
                    enabled = enabled && updates.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("extension-update-all"),
                ) { Text(desktopText("extensions.updateAll", updates.size)) }
            }
        }
        SettingsGroup(Modifier.weight(1f)) {
            if (state.installedEntries.isEmpty()) {
                SettingsInfoRow(desktopText("extensions.installedEmpty"), null)
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("installed-list")) {
                    itemsIndexed(
                        state.installedEntries,
                        key = { _, entry -> "${entry.kind}:${entry.id}" },
                    ) { index, entry ->
                        if (index > 0) SettingsRowDivider()
                        val update = updates[entry.id]
                        SettingsActionRow(
                            title = entry.label,
                            summary = desktopText(
                                "extensions.entrySummary",
                                DesktopSourceLabels.ecosystem(entry.ecosystem),
                                entry.version,
                                entry.sources,
                            ) + (
                                update?.let {
                                    " · " + desktopText("extensions.updateAvailable", it.extension.versionName)
                                } ?: ""
                                ),
                            verticalPadding = 6.dp,
                        ) {
                            if (update != null) {
                                TextButton(
                                    onClick = { controller.applyUpdates() },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("extension-update:${entry.id}"),
                                ) { Text(desktopText("extensions.update")) }
                            }
                            TextButton(
                                onClick = { confirm = entry },
                                enabled = enabled,
                                modifier = Modifier.testTag("extension-uninstall:${entry.id}"),
                            ) { Text(desktopText("extensions.uninstall")) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { entry ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(desktopText("extensions.uninstallTitle", entry.label)) },
            text = { Text(desktopText("extensions.uninstallDetail", entry.sources)) },
            confirmButton = { Button({ confirm = null; controller.uninstall(entry) },
                modifier = Modifier.testTag("extension-uninstall-confirm")) {
                Text(desktopText("extensions.uninstall"))
            } },
            dismissButton = { TextButton({ confirm = null }) { Text(desktopText("common.cancel")) } })
    }
}
