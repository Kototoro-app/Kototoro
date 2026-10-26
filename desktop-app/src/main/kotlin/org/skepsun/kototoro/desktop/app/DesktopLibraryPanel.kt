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
import org.skepsun.kototoro.core.ui.compose.CompactFilterChip
import org.skepsun.kototoro.core.ui.topbar.TopBarIconAction
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.core.ui.compose.CompactFilterChipFlow
import org.skepsun.kototoro.core.ui.compose.FilterFacetChip
import org.skepsun.kototoro.core.ui.compose.FilterFacetDropdown
import org.skepsun.kototoro.core.ui.compose.FilterFacetOption
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.FilterPanelHeader
import org.skepsun.kototoro.core.ui.compose.FilterPanelReset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/** Category ids are database ids (positive); the "all" tab needs one that can never collide. */
private const val AllCategoriesTab = Long.MIN_VALUE

@Composable
internal fun DesktopLibraryPanel(controller: DesktopController, state: DesktopAppState, enabled: Boolean,
    collectionState: org.skepsun.kototoro.list.ui.compose.ContentCollectionState) {
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
    var downloading by remember(history) { mutableStateOf(false) }
    var pickingMigrationSource by remember(history) { mutableStateOf(false) }
    val ecosystems = remember(state.sources) { state.sources.associate { it.source.name to it.ecosystem } }
    // Android's "downloaded" filter reads which works hold stored chapters; the queue owns that state.
    val downloadStates by controller.downloads.state.collectAsState()
    val downloadedIds = remember(downloadStates) { downloadStates.mapTo(HashSet()) { it.record.contentId } }
    // Android's adult filter target per shelf: the favourites and history targets are separate switches.
    val hideNsfw = state.sourceSettings.hidesNsfw(
        if (history) DesktopNsfwTarget.HISTORY else DesktopNsfwTarget.FAVOURITES,
    )
    // Android hands every shelf deriver the same blacklist instance; it hides works, not the shelf's own filters.
    val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
    val entries = remember(state.library, selection, ecosystems, downloadedIds, hideNsfw, blacklist, history) {
        // Android's favourites shelf ranks pinned works first; history has no pin and keeps its own order.
        selection.select(state.library, ecosystems, downloadedIds, hideNsfw, pinnedFirst = !history,
            tagBlacklist = blacklist)
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
                // Android's FAVOURITE action on the history shelf is the same category dialog: a work with no
                // category is not a favourite, so ticking a box favourites it and unticking the last one drops it.
                onAssignCategories = { pickingCategories = true },
                onMarkCompleted = { confirmingComplete = true },
                // Android's pin is a toggle on the favourites shelf: the selected works flip as a group.
                onTogglePin = if (history) null else ({ controller.setLibraryPinned(checkedIds, !allPinned) }),
                onShare = if (history) null else ({ controller.shareLibrarySelection(checkedIds) }),
                onMigrate = if (history) null else ({
                    checkedIds.singleOrNull()?.let { controller.beginMigration(it) }
                }),
                onDownload = { downloading = true },
                onRemove = { confirmingRemove = true },
                onCancel = { selectedIds = emptySet() },
            ) else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TopBarTitleBlock(if (history) desktopText("nav.history") else desktopText("nav.favourites"))
                DesktopSearchField(selection.query, { update(selection.copy(query = it)) }, desktopText("library.search"), enabled = enabled,
                    modifier = Modifier.weight(1f), tag = "library-query")
                // Android's action capsule: source filters, then this page's filter panel and its "more" menu.
                DesktopSourceFilterControls(selection.sourceFilter, state.sources,
                    { update(selection.copy(sourceFilter = it)) }, enabled, if (history) "history" else "library") {
                    TopBarIconAction(painterResource("icons/ic_sort.svg"),
                        if (selection.filterCount == 0) desktopText("library.filterAndSort")
                        else desktopText("library.filterWithCount", selection.filterCount),
                        { drawer = !drawer }, active = selection.filterCount > 0,
                        modifier = Modifier.testTag("library-filters"))
                    var more by remember(history) { mutableStateOf(false) }
                    Box {
                        TopBarIconAction(painterResource("icons/ic_more_vert.svg"), desktopText("common.more"),
                            { more = true }, inactiveTint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("library-more"))
                        DropdownMenu(more, { more = false }) {
                            CompactDropdownMenuItem({ CompactDropdownMenuText(desktopText("common.refresh")) },
                                { more = false; controller.library(history) }, enabled = enabled,
                                modifier = Modifier.testTag("library-refresh"))
                            if (!history) CompactDropdownMenuItem(
                                { CompactDropdownMenuText(desktopText("library.categoriesTitle")) },
                                { more = false; managingCategories = true }, enabled = enabled,
                                modifier = Modifier.testTag("library-categories-manage"))
                        }
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
                        collectionState = collectionState,
                        metaOf = { content ->
                            content.authors.joinToString().ifBlank {
                                sourceTitles[content.source.name] ?: content.source.name
                            }
                        })
                }
                else org.skepsun.kototoro.list.ui.compose.ContentCollectionEmpty(
                    title = if (state.library.entries.isEmpty()) desktopText("library.empty")
                        else desktopText("library.emptyFiltered"),
                    subtitle = if (state.library.entries.isEmpty()) desktopText("library.emptyHint") else null,
                    actionLabel = if (state.library.entries.isNotEmpty()) desktopText("library.clearEmpty") else null,
                    onAction = { update(DesktopLibrarySelection()) },
                    actionModifier = Modifier.testTag("library-clear-empty"),
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable { drawer = false })
            Surface(Modifier.align(Alignment.CenterEnd).width(310.dp).fillMaxHeight()
                .testTag("library-filter-panel"), shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp) {
                // Android's favourites filter panel, group for group: titled cards of compact chips, a facet
                // dropdown for the long source list, and the centred reset at the foot.
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPanelHeader(desktopText("library.filterAndSort"), AndroidStrings["done"], { drawer = false },
                        doneModifier = Modifier.testTag("library-filter-close"))
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPanelGroup(desktopText("library.sort")) {
                                FilterChoiceChips(DesktopLibraryOrder.entries, { it.title }, { selection.order == it },
                                    enabled, { "library-order:${it.name}" }) { update(selection.copy(order = it)) }
                            }
                            FilterPanelGroup(desktopText("library.layout")) {
                                FilterChoiceChips(org.skepsun.kototoro.desktop.runtime.DesktopListMode.entries,
                                    { it.label }, { state.libraryDisplay.listMode == it }, enabled,
                                    { "library-mode:${it.name}" }) {
                                    controller.libraryDisplay(state.libraryDisplay.copy(listMode = it))
                                }
                            }
                            FilterPanelGroup(desktopText("library.progress")) {
                                FilterChoiceChips(DesktopLibraryReading.entries, { it.title },
                                    { selection.reading == it }, enabled, { "library-reading:${it.name}" }) {
                                    update(selection.copy(reading = it))
                                }
                            }
                            val shelfSources = remember(state.library, state.sources) {
                                state.library.entries.map { it.content.source }.distinctBy { it.name }.map { source ->
                                    source.name to (state.sources.firstOrNull { it.source.name == source.name }
                                        ?.displayName ?: source.name)
                                }
                            }
                            FilterPanelGroup(desktopText("library.source")) {
                                val chosen = shelfSources.filter { it.first in selection.sources }
                                FilterFacetDropdown(
                                    label = facetLabel(desktopText("library.source"), chosen.map { it.second }),
                                    options = shelfSources.map { (name, title) ->
                                        FilterFacetOption(name, title, name in selection.sources)
                                    },
                                    onOptionClick = { name ->
                                        update(selection.copy(sources = if (name in selection.sources)
                                            selection.sources - name else selection.sources + name))
                                    },
                                    resetLabel = AndroidStrings["reset_filter"],
                                    onReset = { update(selection.copy(sources = emptySet())) },
                                    optionIcon = { option -> FacetOptionIcon(option.checked) },
                                    leadingIcon = { FacetIcon("icons/ic_filter_menu.svg") },
                                    trailingIcon = { FacetIcon("icons/ic_expand_more.svg") },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("library-sources-open"),
                                    optionModifier = { Modifier.testTag("library-source:$it") },
                                    resetModifier = Modifier.testTag("library-sources-reset"),
                                )
                            }
                            FilterPanelGroup(desktopText("library.tags")) {
                                // The library's tag list is long and searchable, so the facet opens the tag picker.
                                FilterFacetChip(
                                    label = if (selection.tags.isEmpty()) desktopText("library.tagsChoose")
                                        else desktopText("library.tagsSelected", selection.tags.size),
                                    selected = selection.tags.isNotEmpty(),
                                    onClick = { pickingTags = true },
                                    leadingIcon = { FacetIcon("icons/ic_tag.svg") },
                                    trailingIcon = { FacetIcon("icons/ic_expand_more.svg") },
                                    enabled = enabled && tagChoices.isNotEmpty(),
                                    modifier = Modifier.testTag("library-tags-open"),
                                )
                            }
                            if (stateChoices.isNotEmpty()) FilterPanelGroup(desktopText("library.state")) {
                                FilterToggleChips(stateChoices, { desktopContentStatusLabel(it) ?: it },
                                    { it in selection.states }, enabled, { "library-state:$it" }) { name ->
                                    update(selection.copy(states = if (name in selection.states)
                                        selection.states - name else selection.states + name))
                                }
                            }
                            FilterPanelGroup(desktopText("library.download")) {
                                FilterChoiceChips(DesktopLibraryDownload.entries, { it.title },
                                    { selection.download == it }, enabled, { "library-download:${it.name}" }) {
                                    update(selection.copy(download = it))
                                }
                            }
                            FilterPanelGroup(desktopText("library.quick")) {
                                FilterToggleChips(DesktopLibraryMacro.choices(history), { it.title },
                                    { it in selection.macros }, enabled, { "library-macro:${it.name}" }) { macro ->
                                    update(selection.copy(macros = if (macro in selection.macros)
                                        selection.macros - macro else selection.macros + macro))
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier = Modifier.align(Alignment.CenterEnd)
                            .fillMaxHeight().testTag("library-filter-scrollbar"))
                    }
                    FilterPanelReset(desktopText("library.resetFilters"), { update(DesktopLibrarySelection()) },
                        enabled = enabled, modifier = Modifier.testTag("library-filter-reset"))
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
        if (downloading) DesktopLibraryDownloadDialog(
            count = checkedIds.size, options = DesktopShelfDownloadOptions, enabled = enabled,
            onDownload = { option ->
                downloading = false
                controller.downloadLibrarySelection(checkedIds, option)
                selectedIds = emptySet()
            },
            onDismiss = { downloading = false },
        )
        if (pickingTags) DesktopTagPickerDialog(tagChoices, selection.tags,
            onToggle = { tag -> update(selection.copy(tags = if (tag in selection.tags) selection.tags - tag
                else selection.tags + tag)) },
            onClear = { update(selection.copy(tags = emptySet())) },
            onDismiss = { pickingTags = false })
        val migration = state.migration
        if (migration != null) {
            if (pickingMigrationSource) DesktopChoiceSettingsDialog(
                title = desktopText("library.migrate"),
                summary = null,
                bodyTag = "library-migrate-source-pick",
                optionTagPrefix = MIGRATE_SOURCE_OPTION_PREFIX,
                options = migration.sources.map { it.displayName to it.source.name },
                selected = migration.sourceName,
                enabled = enabled,
                onPick = controller::migrationSource,
                onDismiss = { pickingMigrationSource = false },
            )
            DesktopMigrationDialog(migration, enabled = enabled,
                onPickSource = { pickingMigrationSource = true },
                onQuery = controller::migrationQuery,
                onSearch = { controller.searchMigration() },
                onKeepOriginal = controller::migrationKeepOriginal,
                onMigrate = { candidate ->
                    controller.migrateContent(candidate)
                    selectedIds = emptySet()
                },
                onDismiss = controller::closeMigration,
            )
        }
        state.shareLinks?.let { links -> DesktopShareLinksDialog(links, onDismiss = controller::dismissShareLinks) }
    }
}

private val org.skepsun.kototoro.desktop.runtime.DesktopListMode.label: String
    get() = when (this) {
        org.skepsun.kototoro.desktop.runtime.DesktopListMode.GRID -> desktopText("library.layout.grid")
        org.skepsun.kototoro.desktop.runtime.DesktopListMode.LIST -> desktopText("library.layout.list")
    }

/** One chip per value, exactly one of them selected (Android's single-choice quick-filter groups). */
@Composable
private fun <T> FilterChoiceChips(values: List<T>, title: (T) -> String, selected: (T) -> Boolean, enabled: Boolean,
    tag: (T) -> String, onPick: (T) -> Unit) {
    CompactFilterChipFlow {
        values.forEach { value ->
            CompactFilterChip(selected(value), { onPick(value) }, title(value), enabled = enabled,
                modifier = Modifier.testTag(tag(value)).semantics { role = Role.RadioButton })
        }
    }
}

/** One chip per value, any of them on: the groups combine with "or" inside, as Android's chips do. */
@Composable
private fun <T> FilterToggleChips(values: List<T>, title: (T) -> String, selected: (T) -> Boolean, enabled: Boolean,
    tag: (T) -> String, onToggle: (T) -> Unit) {
    CompactFilterChipFlow {
        values.forEach { value ->
            CompactFilterChip(selected(value), { onToggle(value) }, title(value), enabled = enabled,
                modifier = Modifier.testTag(tag(value)))
        }
    }
}

/** Android's facet label: the group title, its one chosen value, or `title · n`. */
private fun facetLabel(title: String, chosen: List<String>): String = when (chosen.size) {
    0 -> title
    1 -> chosen.single()
    else -> AndroidStrings.format("filter_group_selected_count", title, chosen.size)
}

@Composable
private fun FacetIcon(path: String) {
    Icon(painterResource(path), contentDescription = null, modifier = Modifier.size(18.dp))
}

@Composable
private fun FacetOptionIcon(checked: Boolean) {
    if (checked) Icon(painterResource("icons/ic_check.svg"), null, tint = MaterialTheme.colorScheme.primary)
    else Spacer(Modifier.size(24.dp))
}
