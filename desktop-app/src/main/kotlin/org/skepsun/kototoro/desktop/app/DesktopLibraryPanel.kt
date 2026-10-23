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
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.ui.topbar.TopBarTabItem
import org.skepsun.kototoro.core.ui.topbar.TopBarTabsRail
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
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
    // Android's batch selection: it lives with the shelf, so switching shelves or reloading the grid drops ids that
    // are no longer shown instead of acting on hidden works.
    var selectedIds by remember(history) { mutableStateOf(emptySet<Long>()) }
    var pickingCategories by remember(history) { mutableStateOf(false) }
    var confirmingRemove by remember(history) { mutableStateOf(false) }
    var confirmingComplete by remember(history) { mutableStateOf(false) }
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    // Android's "downloaded" filter reads which works hold stored chapters; the queue owns that state.
    val downloadStates by controller.downloads.state.collectAsState()
    val downloadedIds = remember(downloadStates) { downloadStates.mapTo(HashSet()) { it.record.contentId } }
    // Android's adult filter target per shelf: the favourites and history targets are separate switches.
    val hideNsfw = state.sourceSettings.hidesNsfw(
        if (history) DesktopNsfwTarget.HISTORY else DesktopNsfwTarget.FAVOURITES,
    )
    val entries = remember(state.library, selection, ecosystems, downloadedIds, hideNsfw, history) {
        // Android's favourites shelf ranks pinned works first; history has no pin and keeps its own order.
        selection.select(state.library, ecosystems, downloadedIds, hideNsfw, pinnedFirst = !history)
    }
    val pinned = remember(entries) { entries.associate { it.content.id to it.isPinned } }
    val shownIds = remember(entries) { entries.mapTo(LinkedHashSet()) { it.content.id } }
    val checkedIds = remember(selectedIds, shownIds) { selectedIds.intersect(shownIds) }
    val allPinned = remember(checkedIds, pinned) { checkedIds.isNotEmpty() && checkedIds.all { pinned[it] == true } }
    fun toggleSelection(content: SourceContent) {
        selectedIds = if (content.id in selectedIds) selectedIds - content.id else selectedIds + content.id
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
            if (checkedIds.isNotEmpty()) DesktopLibrarySelectionBar(
                count = checkedIds.size, history = history, allPinned = allPinned, enabled = enabled,
                onSelectAll = { selectedIds = LinkedHashSet(shownIds) },
                onAssignCategories = if (history) null else ({ pickingCategories = true }),
                onMarkCompleted = { confirmingComplete = true },
                // Android's pin is a toggle on the favourites shelf: the selected works flip as a group.
                onTogglePin = if (history) null else ({ controller.setLibraryPinned(checkedIds, !allPinned) }),
                onShare = if (history) null else ({ controller.shareLibrarySelection(checkedIds) }),
                onRemove = { confirmingRemove = true },
                onCancel = { selectedIds = emptySet() },
            ) else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    val newChapters = remember(entries, history) {
                        entries.associate { it.content.id to newChaptersBadge(it, history) }
                    }
                    // Android's display options: the shelf renders the poster grid or one work per row, and the
                    // supporting line names the source instead of repeating its raw name.
                    val sourceTitles = remember(state.sources) {
                        state.sources.associate { it.source.name to it.displayName }
                    }
                    ContentGrid(entries.map { it.content }, enabled, controller.session.covers, controller::details,
                        progressOf = { progress[it.id] }, newChaptersOf = { newChapters[it.id] ?: 0 },
                        pinnedOf = { pinned[it.id] == true },
                        selectedIds = checkedIds.takeIf { it.isNotEmpty() },
                        onToggleSelection = ::toggleSelection,
                        listMode = state.libraryDisplay.listMode,
                        metaOf = { content ->
                            content.authors.joinToString().ifBlank {
                                sourceTitles[content.source.name] ?: content.source.name
                            }
                        })
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
                            Text(desktopText("library.layout"), fontWeight = FontWeight.SemiBold)
                            org.skepsun.kototoro.desktop.runtime.DesktopListMode.entries.forEach { mode ->
                                FilterRadio(mode.label, state.libraryDisplay.listMode == mode, enabled,
                                    "library-mode:${mode.name}") {
                                    controller.libraryDisplay(state.libraryDisplay.copy(listMode = mode))
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
                            HorizontalDivider()
                            Text(desktopText("library.quick"), fontWeight = FontWeight.SemiBold)
                            DesktopLibraryMacro.choices(history).forEach { macro ->
                                val selected = macro in selection.macros
                                Row(Modifier.fillMaxWidth().testTag("library-macro:${macro.name}")
                                    .clickable(enabled = enabled) {
                                        update(selection.copy(macros = if (selected) selection.macros - macro
                                            else selection.macros + macro))
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null, enabled = enabled)
                                    Text(macro.title, modifier = Modifier.padding(start = 12.dp))
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
        if (pickingCategories) DesktopCategoryPickerDialog(
            count = checkedIds.size, categories = state.library.categories,
            // Works may sit in different categories; the shared ones are what ticking a box means for all of them.
            initial = remember(checkedIds, state.library) {
                entries.filter { it.content.id in checkedIds }
                    .map { it.categoryIds.toSet() }
                    .reduceOrNull { a, b -> a.intersect(b) }
                    ?: emptySet()
            },
            enabled = enabled,
            onConfirm = { categories ->
                pickingCategories = false
                controller.setFavouriteCategories(checkedIds, categories)
                selectedIds = emptySet()
            },
            onDismiss = { pickingCategories = false },
        )
        if (confirmingRemove) DesktopRemoveSelectionDialog(
            count = checkedIds.size, history = history, enabled = enabled,
            onConfirm = {
                confirmingRemove = false
                if (history) controller.removeHistoryRecords(checkedIds)
                else controller.removeFavourites(checkedIds)
                selectedIds = emptySet()
            },
            onDismiss = { confirmingRemove = false },
        )
        if (confirmingComplete) DesktopMarkCompletedDialog(
            count = checkedIds.size, enabled = enabled,
            onConfirm = {
                confirmingComplete = false
                controller.markLibraryCompleted(checkedIds)
                selectedIds = emptySet()
            },
            onDismiss = { confirmingComplete = false },
        )
        if (pickingTags) DesktopTagPickerDialog(tagChoices, selection.tags,
            onToggle = { tag -> update(selection.copy(tags = if (tag in selection.tags) selection.tags - tag
                else selection.tags + tag)) },
            onClear = { update(selection.copy(tags = emptySet())) },
            onDismiss = { pickingTags = false })
        state.shareLinks?.let { links -> DesktopShareLinksDialog(links, onDismiss = controller::dismissShareLinks) }
    }
}

private val org.skepsun.kototoro.desktop.runtime.DesktopListMode.label: String
    get() = when (this) {
        org.skepsun.kototoro.desktop.runtime.DesktopListMode.GRID -> desktopText("library.layout.grid")
        org.skepsun.kototoro.desktop.runtime.DesktopListMode.LIST -> desktopText("library.layout.list")
    }

@Composable
private fun FilterRadio(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, modifier = Modifier.padding(start = 12.dp))
    }
}
