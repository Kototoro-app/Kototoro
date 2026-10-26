package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTab

import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTabs
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.extensions.SelectedSourcesDeletePlan
import org.skepsun.kototoro.core.extensions.SelectedSourcesNsfwAction
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSplitSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/** The Android source/repository/package structure, using the same CMP tabs and repository cards. */
@Composable
internal fun DesktopExtensionsPanel(controller: DesktopController, closing: Boolean) {
    val state by controller.state.collectAsState()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var available by remember { mutableStateOf(false) }
    val enabled = !state.busy && !closing && !importing
    fun submit(job: kotlinx.coroutines.Job, onSuccess: () -> Unit) {
        importing = true
        addError = null
        scope.launch {
            job.join()
            importing = false
            addError = controller.state.value.error
            if (addError == null) { adding = false; onSuccess() }
        }
    }
    val importedRepositories = remember(state.jsonSources) { importedSourceRepositories(state.jsonSources) }
    androidx.compose.runtime.LaunchedEffect(state.extensionCatalog) {
        if (state.extensionCatalog != null) { tab = 2; available = true }
    }
    Column(Modifier.fillMaxSize().testTag("extensions-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtensionManagementTabs(
                tabs = listOf(
                    ExtensionManagementTab(
                        AndroidStrings.format("sources_tab_title", state.sources.size), state.sources.size),
                    ExtensionManagementTab(
                        AndroidStrings.format("repositories_tab_title", state.repositories.size + importedRepositories.size),
                        state.repositories.size + importedRepositories.size),
                    ExtensionManagementTab(
                        AndroidStrings.format("packages_tab_title", state.installedEntries.size), state.installedEntries.size,
                        state.extensionUpdates.size),
                ),
                selectedTab = tab,
                onTabClick = { tab = it },
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Button({ addError = null; adding = true }, enabled = enabled, modifier = Modifier.testTag("extension-add")) {
                Text(desktopText("extensions.add"))
            }
        }
        when (tab) {
            0 -> ManagedSources(controller, state, enabled)
            1 -> DesktopExtensionRepositories(controller, state, importedRepositories, enabled, { adding = true })
            2 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!available, { available = false }, { Text(desktopText("extensions.installed")) },
                        modifier = Modifier.testTag("extensions-installed-tab"))
                    FilterChip(available, { available = true }, { Text(desktopText("extensions.available")) },
                        modifier = Modifier.testTag("extensions-available-tab"))
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton({ controller.checkExtensionUpdates() }, enabled = enabled,
                        modifier = Modifier.testTag("extension-check-updates")) { Text(desktopText("extensions.checkUpdates")) }
                    if (state.extensionUpdates.isNotEmpty()) {
                        Button({ controller.applyUpdates() }, enabled = enabled,
                            modifier = Modifier.testTag("extension-update-all")) {
                            Text(desktopText("extensions.updateAll", state.extensionUpdates.size))
                        }
                    }
                }
                if (available) {
                    ExtensionCatalog(controller, state, query, { query = it }, enabled, Modifier.weight(1f))
                } else {
                    InstalledExtensions(controller, state, enabled,
                        Modifier.weight(1f))
                }
            }
        }
    }
    state.installPrompt?.let { request ->
        DesktopInstallPromptDialog(request, { enable, remember -> controller.resolveInstallPrompt(enable, remember) },
            { controller.dismissInstallPrompt() })
    }
    if (adding) DesktopExtensionImportDialog(
        enabled = enabled,
        error = addError,
        onDismiss = { adding = false },
        onRepository = { address -> submit(controller.fetchRepository(address)) { tab = 2; available = true } },
        onSource = { address, name -> submit(controller.importJsonSource(address, name)) { tab = 0 } },
        onFile = { path -> submit(controller.importExtensionFile(path)) {
            tab = if (path.fileName.toString().endsWith(".json", ignoreCase = true)) 0 else 2
            available = false
        } },
    )
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
                    SettingsInfoRow(if (query.isNotBlank()) desktopText("extensions.noResults")
                        else desktopText("extensions.catalogEmpty"), null)
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
    // Android's UnifiedPackageList keeps its expanded packages in the screen's own state; the same here, keyed by id.
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    val entries = state.installedEntries.filter {
        query.isBlank() || it.label.contains(query, true) || it.id.contains(query, true) ||
            it.sourceNames.any { name -> name.contains(query, true) }
    }
    val updates = state.extensionUpdates.associateBy { it.extension.packageName }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(desktopText("extensions.installedCount", state.installedEntries.size))
        DesktopSearchField(query, { query = it }, desktopText("extensions.search"),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), tag = "installed-extension-search")
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
            // Android's "test all" button for the enabled sources (plan §184): one row, its own page, since six steps
            // per source need far more room than a dialog row has here.
            SettingsValueRow(
                title = AndroidStrings["source_test_title"],
                summary = desktopText("extensions.testSourcesSummary"),
                value = null,
                enabled = enabled,
                onClick = { controller.showSourceTest() },
                testTag = "extension-test-sources",
            )
        }
        // Composed on demand rather than in a nested lazy list: a second scroll viewport inside a page this short left the
        // removal buttons clipped and unreachable, and the installed set is small enough to lay out row by row.
        SettingsGroup(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("installed-list")) {
            if (entries.isEmpty()) {
                SettingsInfoRow(if (query.isNotBlank()) desktopText("extensions.noResults")
                    else desktopText("extensions.installedEmpty"), null)
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsRowDivider()
                    val update = updates[entry.id]
                    val hidden = expanded - entry.id
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
                        // Android expands the package header itself; the row here also owns update/uninstall buttons,
                        // so its sources hang off a button with the count they would reveal.
                        if (entry.sourceNames.isNotEmpty()) {
                            TextButton(
                                onClick = { expanded = if (entry.id in expanded) hidden else expanded + entry.id },
                                enabled = enabled,
                                modifier = Modifier.testTag("extension-sources:${entry.id}"),
                            ) {
                                Text(
                                    if (entry.id in expanded) desktopText("extensions.hideSources", entry.sources)
                                    else desktopText("extensions.showSources", entry.sources),
                                )
                            }
                        }
                        if (update != null) {
                            TextButton(
                                onClick = { controller.applyUpdates(listOf(update)) },
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
                    if (entry.id in expanded) {
                        entry.sourceNames.forEach { name ->
                            val listing = state.sources.firstOrNull { it.source.name == name }
                            SettingsRowDivider()
                            // Android's source row: the label opens that source's settings, the switch only enables it.
                            SettingsSplitSwitchRow(
                                title = listing?.displayName ?: name,
                                summary = listing?.let {
                                    listOf(
                                        DesktopSourceLabels.contentType(it.source.contentType),
                                        DesktopSourceLabels.ecosystem(it.ecosystem),
                                        it.source.locale,
                                    ).joinToString(" · ")
                                },
                                checked = name !in state.disabledSources,
                                enabled = enabled,
                                onClick = { controller.sourceSettings(name) },
                                onCheckedChange = { controller.setSourceEnabled(name, it) },
                                modifier = Modifier.padding(start = 24.dp),
                                testTag = "extension-source-open:$name",
                                switchTag = "extension-source:$name",
                            )
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

/**
 * Android's unified source list, reduced to what Windows can honour: one switch per source, the enabled/disabled
 * filter and the "apply to everything in the current filter" buttons. A disabled source stays installed (its works,
 * downloads and preference store are untouched) and simply leaves browsing, search, the source filters,
 * recommendations and change-source targets, exactly like Android's enabled-only source list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagedSources(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DesktopSourceEnabledFilter.ALL) }
    var pendingBatch by remember { mutableStateOf<Boolean?>(null) }
    // Android's `SetSelectedSourcesNsfw` dialog: only a mixed selection needs it, a uniform one toggles immediately.
    var pendingNsfw by remember { mutableStateOf(false) }
    // Android's `DeleteSelectedSources` dialog, opened with the plan the current selection folds into.
    var pendingDelete by remember { mutableStateOf<SelectedSourcesDeletePlan?>(null) }
    // Android's `selectedSourceIds` with its own lifetime: a tap on a source in selection mode joins or leaves the set,
    // an empty set ends the mode, and the rows are switches again — exactly Android's `isSelectionMode`.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    val entries = remember(state.sources, state.disabledSources, query, filter) {
        filterSourceSwitchEntries(state.sources, query, filter, state.disabledSources)
    }
    // Android's per-selection bulk primitive: one write for the whole selection and the mode ends with it. The filter
    // follows the side the rows moved to, the same rule the confirmation below follows, so the result stays on screen
    // instead of vanishing right after the user pressed the button.
    fun applySelection(enable: Boolean, names: Set<String>) {
        controller.setSourcesEnabled(names, enable)
        selected = emptySet()
        filter = if (enable) DesktopSourceEnabledFilter.ENABLED else DesktopSourceEnabledFilter.DISABLED
    }
    // Android's NSFW chip writes the per-source override and ends the mode without touching the enabled filter, because
    // an adult flag does not move a row out of any side of the list.
    fun applyNsfwSelection(isNsfw: Boolean, names: Set<String>) {
        controller.setSourcesNsfw(names, isNsfw)
        selected = emptySet()
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(desktopText("sourceList.search")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("source-list-query"),
        )
        // Filtering and bulk controls share one toolbar; the source list owns the remaining viewport.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopSourceEnabledFilter.entries.forEach { option ->
                FilterChip(selected = option == filter, onClick = { filter = option },
                    label = { Text(AndroidStrings[option.stringName()]) },
                    modifier = Modifier.testTag("source-enabled-filter:${option.name}"))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton({ selected = toggleVisibleSourceSelection(selected, entries) },
                enabled = enabled && entries.isNotEmpty(), modifier = Modifier.testTag("source-selection-all")) {
                Text(AndroidStrings[if (everyVisibleSourceSelected(selected, entries)) "deselect_all" else "select_all"])
            }
            if (selecting) {
                Text(AndroidStrings.format("selected_count", selected.size),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("source-selection-count"))
            } else {
                TextButton({ pendingBatch = true },
                    enabled = enabled && entries.any { it.source.name in state.disabledSources },
                    modifier = Modifier.testTag("source-switches-enable-all")) {
                    Text(AndroidStrings["unified_sources_enable_all"])
                }
                TextButton({ pendingBatch = false },
                    enabled = enabled && entries.any { it.source.name !in state.disabledSources },
                    modifier = Modifier.testTag("source-switches-disable-all")) {
                    Text(AndroidStrings["unified_sources_disable_all"])
                }
            }
        }
        if (selecting) {
            // Android's `onEnableSelectedSources`/`onDisableSelectedSources`/`onToggleSelectedSourcesNsfw`/
            // `onTestSelectedSources`: one write for the whole selection, and the mode ends with it.
            val nsfwAction = selectedSourcesNsfwAction(selected, state.sources, state.sourceNsfwOverrides)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { applySelection(true, selected) },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-selection-enable"),
                ) { Text(AndroidStrings["enable"]) }
                TextButton(
                    onClick = { applySelection(false, selected) },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-selection-disable"),
                ) { Text(AndroidStrings["disable"]) }
                TextButton(
                    onClick = {
                        when (nsfwAction) {
                            SelectedSourcesNsfwAction.SET_NSFW -> applyNsfwSelection(true, selected)
                            SelectedSourcesNsfwAction.SET_SFW -> applyNsfwSelection(false, selected)
                            SelectedSourcesNsfwAction.CHOOSE -> pendingNsfw = true
                            SelectedSourcesNsfwAction.NONE -> Unit
                        }
                    },
                    enabled = enabled && nsfwAction != SelectedSourcesNsfwAction.NONE,
                    modifier = Modifier.testTag("source-selection-nsfw"),
                ) { Text(AndroidStrings[selectedSourcesNsfwLabel(nsfwAction)]) }
                TextButton(
                    onClick = {
                        val names = selected.toList()
                        selected = emptySet()
                        // The tester owns its own page and forgets a stale run on entry, so navigate first
                        // and start the run over the selection (Android's `testSources(ids)`).
                        controller.showSourceTest()
                        controller.startSourceTest(names)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-selection-test"),
                ) { Text(AndroidStrings["source_test_action"]) }
                // Android's 删除 asks first, with the package count and the JAR packages it refuses to touch.
                TextButton(
                    onClick = { pendingDelete = sourceDeletePlan(selected, state.installedEntries) },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-selection-delete"),
                ) { Text(AndroidStrings["delete"]) }
                TextButton(
                    onClick = { selected = emptySet() },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-selection-close"),
                ) { Text(desktopText("common.cancel")) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.sources.isEmpty()) desktopText("extensions.sourcesEmpty")
                    else desktopText("sourceList.empty"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)
                .testTag("source-enable-list")) {
                itemsIndexed(entries, key = { _, listing -> listing.source.name }) { index, listing ->
                    if (index > 0) SettingsRowDivider()
                    val name = listing.source.name
                    val summary = listOf(
                        DesktopSourceLabels.contentType(listing.source.contentType),
                        DesktopSourceLabels.ecosystem(listing.ecosystem),
                        listing.source.locale,
                    ).joinToString(" · ")
                    if (selecting) {
                        // Android shares one row for both modes: in selection mode the whole row is the
                        // checkbox, so a tap joins or leaves the selection instead of flipping the switch.
                        val isSelected = name in selected
                        SettingsActionRow(
                            title = listing.displayName,
                            summary = summary,
                            modifier = Modifier
                                .toggleable(
                                    value = isSelected,
                                    enabled = enabled,
                                    role = Role.Checkbox,
                                ) { selected = toggleSourceSelection(selected, name) }
                                .testTag("source-selected:$name"),
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null, enabled = enabled)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsSplitSwitchRow(
                                title = listing.displayName,
                                summary = summary,
                                checked = name !in state.disabledSources,
                                enabled = enabled,
                                onCheckedChange = { controller.setSourceEnabled(name, it) },
                                onClick = { controller.sourceSettings(name) },
                                modifier = Modifier.weight(1f),
                                testTag = "source-settings:$name",
                                switchTag = "source-enabled:$name",
                            )
                            state.jsonSources.firstOrNull { it.id == name }?.let { source ->
                                androidx.compose.material3.IconButton({ controller.removeJsonSource(source.id) },
                                    enabled = enabled, modifier = Modifier.testTag("json-source-remove:${source.id}")) {
                                    androidx.compose.material3.Icon(
                                        org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons.Delete,
                                        contentDescription = desktopText("jsonSources.remove"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingBatch?.let { enable ->
        val names = entries.map { it.source.name }
        val key = if (enable) "enable" else "disable"
        AlertDialog(
            onDismissRequest = { pendingBatch = null },
            modifier = Modifier.testTag("source-switch-batch-confirm"),
            title = { Text(AndroidStrings["unified_sources_${key}_all"]) },
            text = { Text(AndroidStrings.format("unified_sources_${key}_all_confirmation", names.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        controller.setSourcesEnabled(names, enable)
                        // Follow the batch to the side it moved the rows to: otherwise the confirmation empties the
                        // list right after the user pressed it (Android lets the rows drop out of the filter).
                        filter = if (enable) DesktopSourceEnabledFilter.ENABLED else DesktopSourceEnabledFilter.DISABLED
                        pendingBatch = null
                    },
                    modifier = Modifier.testTag("source-switch-batch-ok"),
                ) { Text(AndroidStrings[key]) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatch = null }) { Text(desktopText("common.cancel")) }
            },
        )
    }
    if (pendingNsfw) {
        // Android's `SetSelectedSourcesNsfw`: only a mixed selection asks, and the answer applies to all of it.
        val names = selected
        AlertDialog(
            onDismissRequest = { pendingNsfw = false },
            modifier = Modifier.testTag("source-switch-nsfw-dialog"),
            title = { Text(AndroidStrings["unified_sources_set_nsfw_title"]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { pendingNsfw = false; applyNsfwSelection(true, names) },
                        modifier = Modifier.testTag("source-switch-nsfw-mark"),
                    ) { Text(AndroidStrings["unified_sources_mark_as_nsfw"]) }
                    TextButton(
                        onClick = { pendingNsfw = false; applyNsfwSelection(false, names) },
                        modifier = Modifier.testTag("source-switch-nsfw-unmark"),
                    ) { Text(AndroidStrings["unified_sources_mark_as_sfw"]) }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingNsfw = false }) { Text(desktopText("common.cancel")) }
            },
        )
    }
    val deletePlan = pendingDelete
    if (deletePlan != null) {
        // Android's `UnifiedDeleteSelectedSourcesDialog`: the count of packages, and the JAR plugins it left alone.
        // A plan with nothing removable only tells the user so, so its single button just closes.
        val skipped = deletePlan.skippedNames.joinToString(", ")
        val message = when {
            deletePlan.hasPackages && deletePlan.skippedNames.isNotEmpty() ->
                AndroidStrings.format("unified_sources_delete_selected_with_skipped_jars",
                    deletePlan.packageIds.size, skipped)
            deletePlan.hasPackages ->
                AndroidStrings.format("unified_sources_delete_selected_message", deletePlan.packageIds.size)
            deletePlan.skippedNames.isNotEmpty() ->
                AndroidStrings.format("unified_sources_delete_selected_only_skipped_jars", skipped)
            else -> AndroidStrings["unified_sources_delete_selected_no_packages"]
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            modifier = Modifier.testTag("source-switch-delete-dialog"),
            title = { Text(AndroidStrings["delete"]) },
            text = { Text(message) },
            confirmButton = {
                if (deletePlan.hasPackages) {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            selected = emptySet()
                            controller.uninstallPackages(deletePlan.packageIds)
                        },
                        modifier = Modifier.testTag("source-switch-delete-confirm"),
                    ) { Text(AndroidStrings["delete"]) }
                } else {
                    TextButton(onClick = { pendingDelete = null },
                        modifier = Modifier.testTag("source-switch-delete-close"),
                    ) { Text(desktopText("common.ok")) }
                }
            },
            dismissButton = {
                if (deletePlan.hasPackages) {
                    TextButton(onClick = { pendingDelete = null },
                        modifier = Modifier.testTag("source-switch-delete-cancel"),
                    ) { Text(desktopText("common.cancel")) }
                }
            },
        )
    }
}

/** The Android string naming this filter chip (`all` / `enabled` / `disabled`). */
private fun DesktopSourceEnabledFilter.stringName(): String = when (this) {
    DesktopSourceEnabledFilter.ALL -> "all"
    DesktopSourceEnabledFilter.ENABLED -> "enabled"
    DesktopSourceEnabledFilter.DISABLED -> "disabled"
}
