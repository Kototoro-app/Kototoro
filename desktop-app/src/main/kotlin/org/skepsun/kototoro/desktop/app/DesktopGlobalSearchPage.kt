package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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

/** Pause after the last keystroke before the query goes to every source. */
private const val SEARCH_DEBOUNCE_MS = 500L

/**
 * Android's global search, reached from the home page: one query asked of every enabled source, the answers grouped
 * under the source that gave them as they arrive.
 */
@Composable
internal fun DesktopGlobalSearchPage(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var text by remember { mutableStateOf(state.globalSearch.query) }
    LaunchedEffect(text) {
        if (text.trim() == state.globalSearch.query) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        controller.globalSearch(text)
    }
    val search = state.globalSearch
    val titles = remember(state.sources) { state.sources.associate { it.source.name to it.displayName } }
    val contents = remember(search.results) { search.results.values.flatten() }
    val sourceOf = remember(search.results) {
        search.results.flatMap { (name, items) -> items.map { it.id to name } }.toMap()
    }
    DesktopPageBar(bar = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBarTitleBlock(AndroidStrings["global_search"])
            DesktopSearchField(text, { text = it }, AndroidStrings["search_hint"], modifier = Modifier.weight(1f),
                enabled = enabled, tag = "global-search-query")
            DesktopSourceFilterControls(search.filter, state.sources, controller::globalSearchFilter, enabled, "search")
        }
    }, modifier = Modifier.fillMaxSize().testTag("global-search-panel")) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                search.query.isEmpty() -> Unit
                contents.isEmpty() && search.pending.isEmpty() -> Text(AndroidStrings["nothing_found"],
                    modifier = Modifier.align(Alignment.Center).padding(top = padding.calculateTopPadding()))
                else -> ContentGrid(contents, enabled, controller.session.covers, controller::details,
                    groupOf = { sourceOf[it.id]?.let { name -> titles[name] ?: name } },
                    contentPadding = padding)
            }
            if (search.pending.isNotEmpty()) Text(desktopText("globalSearch.pending", search.pending.size),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("global-search-pending"))
        }
    }
}
