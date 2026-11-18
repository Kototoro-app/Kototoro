package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchHistoryRules
import org.skepsun.kototoro.search.domain.SearchKind

/** Pause after the last keystroke before the query goes to every source. */
private const val SEARCH_DEBOUNCE_MS = 500L

private fun SearchKind.label(): String = when (this) {
    SearchKind.SIMPLE -> AndroidStrings["all"]
    SearchKind.TITLE -> AndroidStrings["title"]
    SearchKind.AUTHOR -> AndroidStrings["author"]
    SearchKind.TAG -> AndroidStrings["tags"]
    SearchKind.ADVANCED -> AndroidStrings["advanced_search"]
}

/**
 * Android's global search, reached from the home page: one query asked of every enabled source on the shared search
 * rules. The works the user already has come first, the sources' answers follow grouped under the source as they
 * arrive, failed sources can be retried and the disabled ones asked too. Earlier queries are offered again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopGlobalSearchPage(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val search = state.globalSearch
    var text by remember { mutableStateOf(search.query) }
    var advanced by remember { mutableStateOf(search.advanced) }
    LaunchedEffect(text, search.kind) {
        if (search.kind == SearchKind.ADVANCED || text.trim() == search.query) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        controller.globalSearch(text)
    }
    LaunchedEffect(advanced) {
        if (search.kind != SearchKind.ADVANCED || advanced == search.advanced) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        controller.globalSearchAdvanced(advanced)
    }
    val titles = remember(state.sources) { state.sources.associate { it.source.name to it.displayName } }
    val libraryIds = remember(search.history, search.favourites) { (search.history + search.favourites).mapTo(HashSet()) { it.id } }
    // The grid keys its cards by work, so a work shown in a library group is left out of the source groups.
    val sourceResults = remember(search.results, libraryIds) {
        search.results.mapValues { (_, works) -> works.filterNot { it.id in libraryIds } }.filterValues { it.isNotEmpty() }
    }
    val contents = remember(search.history, search.favourites, sourceResults) {
        search.history + search.favourites + sourceResults.values.flatten()
    }
    val groups = remember(search.history, search.favourites, sourceResults, titles) {
        buildMap<Long, String> {
            search.history.forEach { put(it.id, AndroidStrings["history"]) }
            search.favourites.forEach { put(it.id, AndroidStrings["favourites"]) }
            sourceResults.forEach { (name, works) -> works.forEach { put(it.id, titles[name] ?: name) } }
        }
    }
    val suggestions = remember(search.recent, text) { SearchHistoryRules.suggest(search.recent, text) }
    val open: (SourceContent) -> Unit = { controller.rememberSearch(); controller.details(it) }
    DesktopPageBar(bar = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TopBarTitleBlock(AndroidStrings["global_search"])
                if (search.kind == SearchKind.ADVANCED) {
                    DesktopSearchField(advanced.title, { advanced = advanced.copy(title = it) }, AndroidStrings["title"],
                        modifier = Modifier.weight(1f), enabled = enabled, tag = "global-search-title")
                    DesktopSearchField(advanced.tags, { advanced = advanced.copy(tags = it) }, AndroidStrings["tags"],
                        modifier = Modifier.weight(1f), enabled = enabled, tag = "global-search-tags")
                    DesktopSearchField(advanced.author, { advanced = advanced.copy(author = it) }, AndroidStrings["author"],
                        modifier = Modifier.weight(1f), enabled = enabled, tag = "global-search-author")
                } else {
                    DesktopSearchField(text, { text = it }, AndroidStrings["search_hint"], modifier = Modifier.weight(1f),
                        enabled = enabled, tag = "global-search-query")
                }
                DesktopSourceFilterControls(search.filter, state.sources, controller::globalSearchFilter, enabled, "search")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchKind.entries.forEach { kind ->
                    FilterChip(selected = search.kind == kind, onClick = { controller.globalSearchKind(kind) },
                        enabled = enabled, label = { Text(kind.label()) }, modifier = Modifier.testTag("global-search-kind:${kind.name}"))
                }
            }
            if (search.kind != SearchKind.ADVANCED && suggestions.isNotEmpty() && (text.isBlank() || !search.isActive)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag("global-search-recent")) {
                    suggestions.forEach { query ->
                        AssistChip(onClick = { text = query; controller.globalSearch(query) }, enabled = enabled,
                            label = { Text(query) })
                    }
                    if (text.isBlank()) TextButton({ controller.clearSearchHistory() }, enabled = enabled,
                        modifier = Modifier.testTag("global-search-clear-history")) { Text(AndroidStrings["clear_search_history"]) }
                }
            }
            if (search.failed.isNotEmpty() && search.pending.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("global-search-failed")) {
                    Text(desktopText("globalSearch.failed", search.failed.size))
                    TextButton({ controller.retryFailedSearch() }, enabled = enabled) { Text(AndroidStrings["retry"]) }
                }
            }
        }
    }, modifier = Modifier.fillMaxSize().testTag("global-search-panel")) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                !search.isActive -> Unit
                contents.isEmpty() && search.pending.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(top = padding.calculateTopPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AndroidStrings["nothing_found"])
                    if (!search.searchedDisabled) OutlinedButton({ controller.searchDisabledSources() }, enabled = enabled,
                        modifier = Modifier.testTag("global-search-disabled")) { Text(AndroidStrings["search_disabled_sources"]) }
                }
                else -> ContentGrid(contents, enabled, controller.session.covers, open,
                    groupOf = { groups[it.id] }, contentPadding = padding)
            }
            if (search.pending.isNotEmpty()) Text(desktopText("globalSearch.pending", search.pending.size),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("global-search-pending"))
            else if (search.isActive && contents.isNotEmpty() && !search.searchedDisabled) {
                OutlinedButton({ controller.searchDisabledSources() }, enabled = enabled,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("global-search-disabled")) {
                    Text(AndroidStrings["search_disabled_sources"])
                }
            }
        }
    }
}
