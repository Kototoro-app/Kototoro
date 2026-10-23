package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.ui.topbar.TopBarTabItem
import org.skepsun.kototoro.core.ui.topbar.TopBarTabsRail
import org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock

/** Category ids are database ids (positive); the "all" tab needs one that can never collide. */
private const val AllCategoriesTab = Long.MIN_VALUE

@Composable
internal fun DesktopLibraryPanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val history = state.screen == DesktopScreen.HISTORY
    val selection = if (history) state.historySelection else state.librarySelection
    var drawer by remember(history) { mutableStateOf(false) }
    var managingCategories by remember(history) { mutableStateOf(false) }
    var pickingTags by remember(history) { mutableStateOf(false) }
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    // Android's "downloaded" filter reads which works hold stored chapters; the queue owns that state.
    val downloadStates by controller.downloads.state.collectAsState()
    val downloadedIds = remember(downloadStates) { downloadStates.mapTo(HashSet()) { it.record.contentId } }
    val entries = remember(state.library, selection, ecosystems, downloadedIds) {
        selection.select(state.library, ecosystems, downloadedIds)
    }
    // Most used tags first: the drawer lists works, so the popular tags stay above the fold.
    val tagChoices = remember(state.library) {
        state.library.entries.flatMap { it.content.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<SourceTag, Int>> { it.value }.thenBy { it.key.title })
            .map { it.key to it.value }
    }
    // Only states the shelf actually holds are offered, in Android's ContentState order.
    val stateChoices = remember(state.library) {
        val present = state.library.entries.mapNotNullTo(HashSet()) { it.content.state }
        DesktopContentStates.filter { it in present }
    }
    fun update(value: DesktopLibrarySelection) = controller.librarySelection(value)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (drawer && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            drawer = false
            true
        } else false
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TopBarTitleBlock(if (history) desktopText("nav.history") else desktopText("nav.favourites"))
                DesktopSearchField(selection.query, { update(selection.copy(query = it)) }, desktopText("library.search"), enabled = enabled,
                    modifier = Modifier.weight(1f), tag = "library-query")
                DesktopSourceFilterControls(selection.sourceFilter, state.sources,
                    { update(selection.copy(sourceFilter = it)) }, enabled, if (history) "history" else "library")
                OutlinedButton({ drawer = !drawer }, modifier = Modifier.testTag("library-filters")) {
                    Text(if (selection.filterCount == 0) desktopText("library.filterAndSort")
                else desktopText("library.filterWithCount", selection.filterCount))
                }
                TextButton({ controller.library(history) }, enabled = enabled) { Text(desktopText("common.refresh")) }
                if (!history) {
                    TextButton({ managingCategories = true }, enabled = enabled,
                        modifier = Modifier.testTag("library-categories-manage")) {
                        Text(desktopText("library.categoriesTitle"))
                    }
                }
            }
            // Android favourites show categories as the tabs rail under the top bar.
            if (!history && state.library.categories.isNotEmpty()) {
                val tabs = remember(state.library.categories) {
                    listOf(TopBarTabItem(AllCategoriesTab, desktopText("library.allCategories"))) +
                        state.library.categories.map { TopBarTabItem(it.id, it.title) }
                }
                TopBarTabsRail(tabs, selection.categoryId ?: AllCategoriesTab,
                    onItemSelected = { id -> if (enabled) update(selection.copy(categoryId = id.takeIf { it != AllCategoriesTab })) },
                    surface = { modifier -> DesktopControlSurface(modifier, RoundedCornerShape(percent = 50)) {} },
                    modifier = Modifier.fillMaxWidth().testTag("library-categories"),
                    itemModifier = { tab ->
                        Modifier.testTag("library-category:${if (tab.id == AllCategoriesTab) "all" else tab.id}")
                    })
            }
            Text(desktopText("library.count", entries.size, state.library.entries.size), color = Muted, fontSize = 12.sp,
                modifier = Modifier.testTag("library-count"))
            Box(Modifier.weight(1f)) {
                if (entries.isNotEmpty()) {
                    val progress = remember(entries) { entries.associate { it.content.id to it.progressPercent } }
                    ContentGrid(entries.map { it.content }, enabled, controller.session.covers, controller::details,
                        progressOf = { progress[it.id] })
                }
                else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.library.entries.isEmpty()) desktopText("library.empty") else desktopText("library.emptyFiltered"),
                        fontWeight = FontWeight.SemiBold)
                    if (state.library.entries.isNotEmpty()) TextButton({ update(DesktopLibrarySelection()) },
                        modifier = Modifier.testTag("library-clear-empty")) { Text(desktopText("library.clearEmpty")) }
                    else Text(desktopText("library.emptyHint"), color = Muted)
                }
            }
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable { drawer = false })
            Surface(Modifier.align(Alignment.CenterEnd).width(310.dp).fillMaxHeight()
                .testTag("library-filter-panel"), shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(desktopText("library.filterAndSort"), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        TextButton({ drawer = false }, modifier = Modifier.testTag("library-filter-close")) { Text(desktopText("common.close")) }
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(desktopText("library.sort"), fontWeight = FontWeight.SemiBold)
                            DesktopLibraryOrder.entries.forEach { order ->
                                FilterRadio(order.title, selection.order == order, enabled, "library-order:${order.name}") {
                                    update(selection.copy(order = order))
                                }
                            }
                            HorizontalDivider()
                            Text(desktopText("library.progress"), fontWeight = FontWeight.SemiBold)
                            DesktopLibraryReading.entries.forEach { reading ->
                                FilterRadio(reading.title, selection.reading == reading, enabled, "library-reading:${reading.name}") {
                                    update(selection.copy(reading = reading))
                                }
                            }
                            HorizontalDivider()
                            Text(desktopText("library.source"), fontWeight = FontWeight.SemiBold)
                            state.library.entries.map { it.content.source }.distinctBy { it.name }.forEach { source ->
                                val title = state.sources.firstOrNull { it.source.name == source.name }?.displayName ?: source.name
                                val selected = source.name in selection.sources
                                Row(Modifier.fillMaxWidth().testTag("library-source:${source.name}")
                                    .clickable(enabled = enabled) {
                                        update(selection.copy(sources = if (selected) selection.sources - source.name
                                            else selection.sources + source.name))
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null, enabled = enabled)
                                    Text(title, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                            HorizontalDivider()
                            Text(desktopText("library.tags"), fontWeight = FontWeight.SemiBold)
                            OutlinedButton({ pickingTags = true }, enabled = enabled && tagChoices.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().testTag("library-tags-open")) {
                                Text(if (selection.tags.isEmpty()) desktopText("library.tagsChoose")
                                else desktopText("library.tagsSelected", selection.tags.size))
                            }
                            if (stateChoices.isNotEmpty()) {
                                HorizontalDivider()
                                Text(desktopText("library.state"), fontWeight = FontWeight.SemiBold)
                                stateChoices.forEach { name ->
                                    val selected = name in selection.states
                                    Row(Modifier.fillMaxWidth().testTag("library-state:$name")
                                        .clickable(enabled = enabled) {
                                            update(selection.copy(states = if (selected) selection.states - name
                                                else selection.states + name))
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(selected, onCheckedChange = null, enabled = enabled)
                                        Text(desktopContentStatusLabel(name) ?: name,
                                            modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text(desktopText("library.download"), fontWeight = FontWeight.SemiBold)
                            DesktopLibraryDownload.entries.forEach { download ->
                                FilterRadio(download.title, selection.download == download, enabled,
                                    "library-download:${download.name}") {
                                    update(selection.copy(download = download))
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier = Modifier.align(Alignment.CenterEnd)
                            .fillMaxHeight().testTag("library-filter-scrollbar"))
                    }
                    OutlinedButton({ update(DesktopLibrarySelection()) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("library-filter-reset")) { Text(desktopText("library.resetFilters")) }
                }
            }
        }
        if (managingCategories) DesktopCategoryDialog(controller, state) { managingCategories = false }
        if (pickingTags) DesktopTagPickerDialog(tagChoices, selection.tags,
            onToggle = { tag -> update(selection.copy(tags = if (tag in selection.tags) selection.tags - tag
                else selection.tags + tag)) },
            onClear = { update(selection.copy(tags = emptySet())) },
            onDismiss = { pickingTags = false })
    }
}

@Composable
private fun FilterRadio(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, modifier = Modifier.padding(start = 12.dp))
    }
}
