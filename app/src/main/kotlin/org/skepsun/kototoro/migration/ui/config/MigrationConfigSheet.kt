package org.skepsun.kototoro.migration.ui.config

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.ui.migrationTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationConfigSheet(
    ids: LongArray,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    onManageSources: () -> Unit,
    viewModel: MigrationConfigViewModel = hiltViewModel(key = "migration-config-${ids.contentHashCode()}"),
) {
    LaunchedEffect(ids) { viewModel.load(ids) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingFamily by remember { mutableStateOf<ContentTypeFamily?>(null) }
    // Same floating glass sheet as the display options sheet.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        KototoroSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = GlassDefaults.prominentStyle().copy(containerAlpha = 0.8f, minimumContainerAlpha = 0.6f),
        ) {
            val family = editingFamily?.let { f -> state.families.firstOrNull { it.family == f } }
            AnimatedContent(
                targetState = family,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "migration-config-page",
            ) { editing ->
                if (editing != null) {
                    SourcePicker(
                        family = editing,
                        onToggle = { viewModel.toggleSource(editing.family, it) },
                        onPreset = { viewModel.selectPreset(editing.family, it) },
                        onDone = { editingFamily = null },
                    )
                } else {
                    ConfigContent(
                        state = state,
                        viewModel = viewModel,
                        onEdit = { editingFamily = it },
                        onManageSources = onManageSources,
                        onStart = {
                            viewModel.save()
                            onStart()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigContent(
    state: MigrationConfigState,
    viewModel: MigrationConfigViewModel,
    onEdit: (ContentTypeFamily) -> Unit,
    onManageSources: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 20.dp),
    ) {
        SheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
        Text(
            stringResource(R.string.migration_config_title, state.count),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.originSourceNames.isNotEmpty()) {
            val names = state.originSourceNames.map { ContentSource(it).migrationTitle(context) }
            Text(
                text = if (names.size <= MAX_ORIGIN_NAMES) {
                    names.joinToString("、")
                } else {
                    stringResource(
                        R.string.migration_config_from_sources,
                        names.take(MAX_ORIGIN_NAMES).joinToString("、"),
                        names.size,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        SectionLabel(stringResource(R.string.migration_config_sources))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.families.forEach { family ->
                FamilySourcesCard(
                    family = family,
                    showFamilyLabel = state.families.size > 1,
                    onClick = { onEdit(family.family) },
                    onManageSources = onManageSources,
                )
            }
        }

        SectionLabel(stringResource(R.string.migration_config_data))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MigrationDataFlag.entries.forEach { flag ->
                val locked = flag == MigrationDataFlag.CATEGORIES
                val selected = flag in state.flags
                FilterChip(
                    selected = selected,
                    onClick = { if (!locked) viewModel.toggleFlag(flag) },
                    label = { Text(stringResource(flag.titleRes())) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                when {
                                    locked -> R.drawable.ic_lock
                                    selected -> R.drawable.ic_check
                                    else -> flag.iconRes()
                                },
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        Text(
            stringResource(R.string.migration_config_categories_locked),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        SectionLabel(stringResource(R.string.migration_config_match))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MatchModeCard(
                iconRes = R.drawable.ic_bolt,
                title = stringResource(R.string.migration_match_first_hit),
                description = stringResource(R.string.migration_match_first_hit_desc),
                selected = state.matchMode == MatchMode.FIRST_HIT,
                onClick = { viewModel.setMatchMode(MatchMode.FIRST_HIT) },
                modifier = Modifier.weight(1f),
            )
            MatchModeCard(
                iconRes = R.drawable.ic_book_page,
                title = stringResource(R.string.migration_match_most_chapters),
                description = stringResource(R.string.migration_match_most_chapters_desc),
                selected = state.matchMode == MatchMode.MOST_CHAPTERS,
                onClick = { viewModel.setMatchMode(MatchMode.MOST_CHAPTERS) },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            modifier = Modifier.padding(top = 16.dp),
        )
        val arrowRotation by animateFloatAsState(if (showMore) 180f else 0f, label = "more-arrow")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showMore = !showMore }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.migration_config_more),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation),
            )
        }
        AnimatedVisibility(showMore) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedTextField(
                    value = state.extraQuery,
                    onValueChange = viewModel::setExtraQuery,
                    singleLine = true,
                    label = { Text(stringResource(R.string.migration_extra_query)) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    stringResource(R.string.migration_deep_search),
                    state.deepSearch,
                    viewModel::setDeepSearch,
                    stringResource(R.string.migration_deep_search_summary),
                )
                SwitchRow(stringResource(R.string.migration_hide_unmatched), state.hideUnmatched, viewModel::setHideUnmatched)
                SwitchRow(
                    stringResource(R.string.migration_hide_without_updates),
                    state.hideWithoutUpdates,
                    viewModel::setHideWithoutUpdates,
                )
                SwitchRow(stringResource(R.string.migration_duplicate_check), state.duplicateCheck, viewModel::setDuplicateCheck)
            }
        }

        Button(
            onClick = onStart,
            enabled = state.canStart,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.migration_start), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** One tappable card per content family: family label, stacked source icons and a summary. */
@Composable
private fun FamilySourcesCard(
    family: FamilySources,
    showFamilyLabel: Boolean,
    onClick: () -> Unit,
    onManageSources: () -> Unit,
) {
    val context = LocalContext.current
    val empty = family.available.isEmpty()
    Surface(
        onClick = if (empty) onManageSources else onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showFamilyLabel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(family.family.iconRes()),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                R.string.migration_config_family,
                                stringResource(family.family.titleRes()),
                                family.contentCount,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                }
                when {
                    empty -> Text(
                        stringResource(R.string.migration_no_available_sources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    family.selected.isEmpty() -> Text(
                        stringResource(R.string.migration_pick_source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        StackedSourceIcons(family.selected.take(MAX_STACKED_ICONS))
                        Spacer(Modifier.width(10.dp))
                        val names = family.selected.take(MAX_VISIBLE_SOURCES).map { ContentSource(it).migrationTitle(context) }
                        Text(
                            text = if (family.selected.size <= MAX_VISIBLE_SOURCES) {
                                names.joinToString("、")
                            } else {
                                stringResource(R.string.migration_sources_summary, names.joinToString("、"), family.selected.size)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (empty) {
                Text(
                    stringResource(R.string.manage_sources),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_arrow_forward),
                    contentDescription = stringResource(R.string.migration_config_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StackedSourceIcons(names: List<String>) {
    Box {
        names.forEachIndexed { index, name ->
            Box(
                Modifier
                    .offset(x = (index * 18).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp),
            ) {
                ContentSourceIcon(source = ContentSource(name), modifier = Modifier.size(24.dp).clip(CircleShape))
            }
        }
        // Reserve the width the offsets draw into.
        Spacer(Modifier.width((28 + (names.size - 1).coerceAtLeast(0) * 18).dp))
    }
}

@Composable
private fun MatchModeCard(
    iconRes: Int,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (selected) R.drawable.ic_check else iconRes),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SourcePicker(
    family: FamilySources,
    onToggle: (String) -> Unit,
    onPreset: (SourcePreset) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(bottom = 12.dp)) {
        SheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(
                    painterResource(R.drawable.ic_arrow_forward),
                    contentDescription = null,
                    modifier = Modifier.rotate(180f),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.migration_sources_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.migration_selected_count, family.selected.size) + " · " +
                        stringResource(R.string.migration_sources_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(SourcePreset.PINNED, SourcePreset.ALL, SourcePreset.NONE).forEach { preset ->
                AssistChip(
                    onClick = { onPreset(preset) },
                    label = {
                        Text(
                            stringResource(
                                when (preset) {
                                    SourcePreset.ALL, SourcePreset.ENABLED -> R.string.migration_select_all
                                    SourcePreset.PINNED -> R.string.migration_select_pinned
                                    SourcePreset.NONE -> R.string.migration_select_none
                                },
                            ),
                        )
                    },
                )
            }
        }
        LazyColumn {
            items(family.available, key = { it.name }) { source ->
                val order = family.selected.indexOf(source.name)
                val checked = order >= 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(source.name) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContentSourceIcon(source = source, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        source.migrationTitle(context),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (checked) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${order + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Checkbox(checked = false, onCheckedChange = { onToggle(source.name) }, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun MigrationDataFlag.titleRes(): Int = when (this) {
    MigrationDataFlag.CATEGORIES -> R.string.migration_flag_categories
    MigrationDataFlag.PROGRESS -> R.string.migration_flag_progress
    MigrationDataFlag.TRACKING -> R.string.migration_flag_tracking
    MigrationDataFlag.NOTES -> R.string.migration_flag_notes
    MigrationDataFlag.STATS -> R.string.migration_flag_stats
}

private fun MigrationDataFlag.iconRes(): Int = when (this) {
    MigrationDataFlag.CATEGORIES -> R.drawable.ic_lock
    MigrationDataFlag.PROGRESS -> R.drawable.ic_book_page
    MigrationDataFlag.TRACKING -> R.drawable.ic_sync
    MigrationDataFlag.NOTES -> R.drawable.ic_bookmark
    MigrationDataFlag.STATS -> R.drawable.ic_sort
}

private fun ContentTypeFamily.titleRes(): Int = when (this) {
    ContentTypeFamily.MANGA -> R.string.content_type_manga
    ContentTypeFamily.NOVEL -> R.string.content_type_novel
    ContentTypeFamily.VIDEO -> R.string.content_type_video
    ContentTypeFamily.OTHER -> R.string.content_type_other
}

private fun ContentTypeFamily.iconRes(): Int = when (this) {
    ContentTypeFamily.MANGA -> R.drawable.ic_content_manga
    ContentTypeFamily.NOVEL -> R.drawable.ic_content_novel
    ContentTypeFamily.VIDEO -> R.drawable.ic_content_video
    ContentTypeFamily.OTHER -> R.drawable.ic_filter_content_type
}

private const val MAX_VISIBLE_SOURCES = 3
private const val MAX_STACKED_ICONS = 4
private const val MAX_ORIGIN_NAMES = 2
