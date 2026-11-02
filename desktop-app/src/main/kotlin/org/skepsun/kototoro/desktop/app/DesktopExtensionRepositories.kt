package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.extensions.ExtensionRepositoryCard
import org.skepsun.kototoro.desktop.runtime.DesktopJsonSource
import org.skepsun.kototoro.tvbox.TvBoxStoredConfig

internal data class DesktopImportedSourceRepository(val address: String, val title: String, val sources: List<DesktopJsonSource>)

/** Existing TVBox documents already carry their origin; no parallel repository store or migration is needed. */
internal fun importedSourceRepositories(sources: List<DesktopJsonSource>): List<DesktopImportedSourceRepository> =
    sources.map { source -> source to runCatching { TvBoxStoredConfig.parse(source.config) }.getOrNull() }
        .groupBy { (source, config) -> config?.sourceLocator?.takeIf(String::isNotBlank) ?: source.api ?: source.id }
        .map { (address, entries) ->
            DesktopImportedSourceRepository(address, entries.first().second?.sourceTitle ?: entries.first().first.name,
                entries.map { it.first })
        }

@Composable
internal fun DesktopExtensionRepositories(
    controller: DesktopController,
    state: DesktopAppState,
    imported: List<DesktopImportedSourceRepository>,
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<DesktopImportedSourceRepository?>(null) }
    val repositories = state.repositories.filter { query.isBlank() || it.name.contains(query, true) || it.indexUrl.contains(query, true) }
    val configs = imported.filter { query.isBlank() || it.title.contains(query, true) || it.address.contains(query, true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopSearchField(query, { query = it }, desktopText("extensions.findRepository"),
            modifier = Modifier.fillMaxWidth(), tag = "repository-search")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("repository-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (repositories.isEmpty() && configs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (query.isBlank()) desktopText("extensions.repositoriesEmpty") else desktopText("extensions.noResults"))
                    if (query.isBlank()) Button(onAdd, enabled = enabled) { Text(desktopText("extensions.add")) }
                }
            }
            items(repositories, key = { "extension:${it.indexUrl}" }) { repo ->
                ExtensionRepositoryCard(title = repo.name, location = repo.indexUrl,
                    leading = { RepositoryIcon() },
                    badges = { Text(desktopText("extensions.repositoryType"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary) },
                    actions = {
                        TextButton({ controller.fetchRepository(repo.indexUrl) }, enabled = enabled,
                            modifier = Modifier.testTag("repository:${repo.indexUrl}")) { Text(desktopText("extensions.browseRepository")) }
                        TextButton({ controller.removeRepository(repo) }, enabled = enabled,
                            modifier = Modifier.testTag("repository-remove:${repo.indexUrl}")) { Text(AndroidStrings["delete"]) }
                    })
            }
            items(configs, key = { "tvbox:${it.address}" }) { repo ->
                ExtensionRepositoryCard(title = repo.title, location = repo.address,
                    leading = { RepositoryIcon() },
                    badges = { Text("TVBox / CMS", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                        Text(desktopText("extensions.repositorySources", repo.sources.size),
                            style = MaterialTheme.typography.labelSmall) },
                    actions = {
                        if (repo.address.startsWith("http://") || repo.address.startsWith("https://")) {
                            TextButton({ controller.importJsonSource(repo.address, repo.title) }, enabled = enabled,
                                modifier = Modifier.testTag("repository:${repo.address}")) { Text(desktopText("common.refresh")) }
                        }
                        TextButton({ removing = repo }, enabled = enabled,
                            modifier = Modifier.testTag("repository-remove:${repo.address}")) { Text(AndroidStrings["delete"]) }
                    })
            }
        }
    }
    removing?.let { repo ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(desktopText("extensions.removeSourceRepository", repo.title)) },
            text = { Text(desktopText("extensions.removeSourceRepositoryHint", repo.sources.size)) },
            confirmButton = { TextButton({ removing = null; controller.removeJsonSources(repo.sources.map { it.id }) },
                enabled = enabled, modifier = Modifier.testTag("repository-remove-confirm")) { Text(AndroidStrings["delete"]) } },
            dismissButton = { TextButton({ removing = null }) { Text(desktopText("common.cancel")) } })
    }
}

@Composable
private fun RepositoryIcon() {
    Icon(painterResource("icons/ic_extension.svg"), contentDescription = null,
        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
}
