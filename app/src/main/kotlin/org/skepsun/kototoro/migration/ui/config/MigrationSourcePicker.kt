package org.skepsun.kototoro.migration.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.FastScrollTouchWidth
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.migration.ui.migrationTitle

@Composable
internal fun MigrationSourcePicker(
    family: FamilySources,
    onToggle: (String) -> Unit,
    onPreset: (SourcePreset, List<String>) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var filters by remember { mutableStateOf(SourcePickerFilters()) }
    var expanded by remember { mutableStateOf(false) }
    val entries = remember(family.available, context) {
        val identifier = SourceTypeIdentifier()
        family.available.map { source ->
            SourcePickerEntry(source, source.migrationTitle(context),
                source.locale.orEmpty().replace('_', '-').lowercase(Locale.ROOT), identifier.getSourceType(source.name))
        }
    }
    val selected = remember(family.selected) { family.selected.toHashSet() }
    val selectionForFiltering = if (filters.selection == SourceSelectionFilter.ALL) emptySet() else selected
    val visible = remember(entries, filters, selectionForFiltering, family.pinned) {
        filterPickerEntries(entries, filters, selectionForFiltering, family.pinned)
    }
    val languages = remember(entries) { entries.map { it.language }.distinct().sorted() }
    val types = remember(entries) { entries.map { it.type }.distinct() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Filter changes intentionally start at the first matching result; selection updates retain the anchor.
    LaunchedEffect(filters) { listState.scrollToItem(0) }
    val visibleNames = remember(visible) { visible.map { it.source.name } }
    val scrollbarLabel = stringResource(R.string.migration_sources_fast_scroll)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val panelHeight = (maxHeight * 0.9f).coerceAtMost(820.dp)
        Column(Modifier.fillMaxWidth().height(panelHeight).padding(bottom = 8.dp)) {
            Column(Modifier.heightIn(max = panelHeight * 0.58f).verticalScroll(rememberScrollState())) {
                SheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_forward), stringResource(R.string.back),
                            Modifier.rotate(180f))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.migration_sources_picker_title),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.migration_selected_of, selected.size, entries.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
                }
                Text(stringResource(R.string.migration_sources_picker_hint),
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = filters.query, onValueChange = { filters = filters.copy(query = it) },
                    placeholder = { Text(stringResource(R.string.search_sources)) }, singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceSelectionFilter.entries.forEach { selection ->
                        FilterChip(selected = filters.selection == selection,
                            onClick = { filters = filters.copy(selection = selection) },
                            label = { Text(stringResource(selection.labelRes())) })
                    }
                    FilterChip(selected = filters.pinnedOnly,
                        onClick = { filters = filters.copy(pinnedOnly = !filters.pinnedOnly) },
                        label = { Text(stringResource(R.string.migration_select_pinned)) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = !expanded }) {
                        val count = if (filters.activeCount > 0) " (${filters.activeCount})" else ""
                        Text(stringResource(R.string.more_filters) + count)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = visible.isNotEmpty(), onClick = { onPreset(SourcePreset.ALL, visibleNames) }) {
                        Text(stringResource(R.string.migration_select_visible))
                    }
                    TextButton(enabled = visible.any { it.source.name in selected },
                        onClick = { onPreset(SourcePreset.NONE, visibleNames) }) {
                        Text(stringResource(R.string.migration_clear_visible))
                    }
                }
                if (expanded) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickerDropdown(stringResource(R.string.language) + ": " + languageTitle(filters.language),
                            listOf(null) + languages, filters.language, { languageTitle(it) }) {
                            filters = filters.copy(language = it)
                        }
                        PickerDropdown(stringResource(R.string.source_type) + ": " +
                            (filters.type?.let { stringResource(it.labelRes()) }
                                ?: stringResource(R.string.migration_select_all)),
                            listOf(null) + types, filters.type,
                            { it?.let { type -> stringResource(type.labelRes()) }
                                ?: stringResource(R.string.migration_select_all) }) {
                            filters = filters.copy(type = it)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.migration_visible_count, visible.size, entries.size),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filters.query.isNotEmpty() || filters.activeCount > 0) {
                    TextButton(onClick = { filters = SourcePickerFilters(alphabetical = filters.alphabetical) }) {
                        Text(stringResource(R.string.reset_filter))
                    }
                }
                FilterChip(selected = filters.alphabetical,
                    onClick = { filters = filters.copy(alphabetical = !filters.alphabetical) },
                    label = { Text(stringResource(R.string.sort_by_name_label)) })
            }
            if (filters.alphabetical && visible.isNotEmpty()) {
                val initials = visible.mapIndexed { index, entry -> entry.initial to index }.distinctBy { it.first }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    initials.forEach { (initial, index) ->
                        TextButton(onClick = { scope.launch { listState.scrollToItem(index) } },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.widthIn(min = 36.dp)) {
                            Text(initial, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (visible.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.no_sources_found_desc), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = FastScrollTouchWidth + 4.dp,
                            bottom = 8.dp)) {
                        items(visible, key = { it.source.name }) { entry ->
                            PickerSourceRow(entry, family.selected.indexOf(entry.source.name), onToggle)
                        }
                    }
                    VerticalScrollbar(state = listState, alwaysVisible = true, draggable = true,
                        modifier = Modifier.semantics { contentDescription = scrollbarLabel },
                        labelProvider = { index -> visible.getOrNull(index)?.title.orEmpty() })
                }
            }
        }
    }
}

@Composable
private fun PickerSourceRow(entry: SourcePickerEntry, order: Int, onToggle: (String) -> Unit) {
    val checked = order >= 0
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
        .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle(entry.source.name) })
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ContentSourceIcon(entry.source, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(languageTitle(entry.language) + " · " + stringResource(entry.type.labelRes()),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (checked) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else Checkbox(checked = false, onCheckedChange = null)
        }
    }
}

@Composable
private fun <T> PickerDropdown(
    title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(title) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = {
                    Text(label(option), fontWeight = if (option == selected) FontWeight.Bold else null)
                },
                    onClick = { open = false; onSelect(option) })
            }
        }
    }
}

@Composable
private fun languageTitle(tag: String?): String = when {
    tag == null -> stringResource(R.string.all_languages)
    tag.isBlank() -> stringResource(R.string.various_languages)
    else -> Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifBlank { tag }
}

private fun SourceSelectionFilter.labelRes(): Int = when (this) {
    SourceSelectionFilter.ALL -> R.string.migration_select_all
    SourceSelectionFilter.SELECTED -> R.string.migration_filter_selected
    SourceSelectionFilter.UNSELECTED -> R.string.migration_filter_unselected
}

private fun SourceType.labelRes(): Int = when (this) {
    SourceType.NATIVE -> R.string.source_type_native
    SourceType.JSON_LEGADO -> R.string.source_type_legado
    SourceType.JSON_TVBOX -> R.string.source_type_tvbox
    SourceType.JSON_JS -> R.string.source_type_js_source
    SourceType.JSON_LNREADER -> R.string.source_type_lnreader
    SourceType.EXTERNAL -> R.string.source_group_external
    SourceType.MIHON -> R.string.source_type_mihon
    SourceType.ANIYOMI -> R.string.source_type_aniyomi
    SourceType.IREADER -> R.string.source_type_ireader
    SourceType.TSUNDOKU -> R.string.source_type_tsundoku
    SourceType.CLOUDSTREAM -> R.string.source_type_cloudstream
}
