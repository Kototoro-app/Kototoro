package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceKind
import org.skepsun.kototoro.space.domain.primarySpaceKind

data class SpacesSettingsUiState(
    val spacesEnabled: Boolean,
    val switcherPosition: SpaceSwitcherPosition,
)

@Composable
fun SpacesSettingsRoute(
    settings: AppSettings,
    modifier: Modifier = Modifier,
    viewModel: SpacesSettingsViewModel = hiltViewModel(),
) {
    val definitions by viewModel.uiState.collectAsStateWithLifecycle()
    val state = SpacesSettingsUiState(
        spacesEnabled = settings.observeAsState(AppSettings.KEY_ENTITY_SPACE_ENABLED) { isEntitySpaceEnabled }.value,
        switcherPosition = settings.observeAsState(AppSettings.KEY_SPACE_SWITCHER_POSITION) {
            spaceSwitcherPosition
        }.value,
    )
    SpacesSettingsScreen(
        state = state,
        definitions = definitions,
        onSpacesEnabledChange = {
            settings.isEntitySpaceEnabled = it
            settings.isSpaceSwitcherEnabled = it
        },
        onSwitcherPositionChange = { settings.spaceSwitcherPosition = it },
        onCreate = viewModel::create,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onMove = viewModel::move,
        modifier = modifier,
    )
}

@Composable
fun SpacesSettingsScreen(
    state: SpacesSettingsUiState,
    definitions: SpaceDefinitionsUiState,
    onSpacesEnabledChange: (Boolean) -> Unit,
    onSwitcherPositionChange: (SpaceSwitcherPosition) -> Unit,
    onCreate: (SpaceContext) -> Unit,
    onSave: (SpaceContext) -> Unit,
    onDelete: (SpaceContext) -> Unit,
    onMove: (SpaceContext, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<SpaceContext?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SpaceContext?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(20.dp),
                end = SettingsContentHorizontalPadding,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsPreferenceGroup(
                    title = stringResource(R.string.spaces),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.spaces_enabled),
                            iconRes = R.drawable.ic_list_group,
                            summary = stringResource(R.string.spaces_enabled_summary),
                            checked = state.spacesEnabled,
                            onCheckedChange = onSpacesEnabledChange,
                        )
                    }
                    item {
                        SettingsChoicePreference(
                            title = stringResource(R.string.space_switcher_position),
                            iconRes = R.drawable.ic_pin,
                            value = state.switcherPosition,
                            options = listOf(
                                SettingsChoiceOption(
                                    SpaceSwitcherPosition.TOP_RIGHT,
                                    stringResource(R.string.space_switcher_position_top_right),
                                ),
                                SettingsChoiceOption(
                                    SpaceSwitcherPosition.CENTER_RIGHT,
                                    stringResource(R.string.space_switcher_position_center_right),
                                ),
                                SettingsChoiceOption(
                                    SpaceSwitcherPosition.TOP_LEFT,
                                    stringResource(R.string.space_switcher_position_top_left),
                                ),
                                SettingsChoiceOption(
                                    SpaceSwitcherPosition.CENTER_LEFT,
                                    stringResource(R.string.space_switcher_position_center_left),
                                ),
                            ),
                            enabled = state.spacesEnabled,
                            onValueChange = onSwitcherPositionChange,
                        )
                    }
                }
            }
            if (state.spacesEnabled) {
                item {
                    SettingsPreferenceGroup(
                        title = stringResource(R.string.custom_spaces),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val customSpaces = definitions.spaces.filterNot(SpaceContext::isBuiltIn)
                        definitions.spaces.forEach { space ->
                            val isFirstCustom = customSpaces.firstOrNull()?.id == space.id
                            val isLastCustom = customSpaces.lastOrNull()?.id == space.id
                            item {
                                SpaceDefinitionRow(
                                    space = space,
                                    canMoveUp = !isFirstCustom,
                                    canMoveDown = !isLastCustom,
                                    onEnabledChange = { onSave(space.copy(enabled = it)) },
                                    onEdit = { editing = space },
                                    onDelete = { deleting = space },
                                    onMove = { onMove(space, it) },
                                )
                            }
                        }
                        item {
                            SettingsActionPreference(
                                title = stringResource(R.string.add_custom_space),
                                summary = stringResource(R.string.custom_space_limit, 16),
                                iconRes = R.drawable.ic_add,
                                enabled = definitions.canCreate,
                                showChevron = false,
                                onClick = { creating = true },
                            )
                        }
                    }
                }
            }
        }
    }
    val dialogSpace = editing ?: if (creating) emptyCustomSpace() else null
    dialogSpace?.let { space ->
        SpaceEditorDialog(
            initial = space,
            availableLanguages = definitions.availableLanguages,
            onDismiss = { editing = null; creating = false },
            onConfirm = {
                if (creating) onCreate(it) else onSave(it)
                editing = null
                creating = false
            },
        )
    }
    deleting?.let { space ->
        SettingsAlertDialog(
            title = stringResource(R.string.delete_custom_space),
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.delete_custom_space_message, space.title.orEmpty())) },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        onDelete(space)
                        deleting = null
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { deleting = null },
                )
            },
        )
    }
}

@Composable
private fun SpaceDefinitionRow(
    space: SpaceContext,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !space.isBuiltIn, onClick = onEdit)
            .settingsPreferenceLayout(enabled = space.enabled || space.isBuiltIn),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLeadingIcon(
            imageVector = Icons.Default.Edit,
            iconRes = space.iconRes(),
            tone = space.iconTone(),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = space.title?.takeIf { it.isNotBlank() } ?: stringResource(space.kind.defaultTitleRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildSpaceSummary(space),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (space.isBuiltIn) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.built_in_space),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            Switch(
                checked = space.enabled,
                onCheckedChange = onEnabledChange,
                colors = settingsSwitchColors(),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = if (isArtworkBackground) {
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        },
                        enabled = canMoveUp,
                        onClick = {
                            menuExpanded = false
                            onMove(-1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        },
                        enabled = canMoveDown,
                        onClick = {
                            menuExpanded = false
                            onMove(1)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpaceEditorDialog(
    initial: SpaceContext,
    availableLanguages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (SpaceContext) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val allContentTypes = remember { ContentType.entries.filterNot { it == ContentType.OTHER } }

    SettingsAlertDialog(
        title = stringResource(
            if (initial.id.value == "custom:draft") R.string.add_custom_space else R.string.edit_custom_space,
        ),
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = draft.title.orEmpty(),
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text(stringResource(R.string.name)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (!draft.title.isNullOrEmpty()) {
                            IconButton(onClick = { draft = draft.copy(title = "") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(android.R.string.cancel),
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Content Types Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.content_types),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (draft.allowedContentTypes.isEmpty()) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                            ) {
                                Text(
                                    text = draft.allowedContentTypes.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (draft.allowedContentTypes.isEmpty()) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val isAllSelected = draft.allowedContentTypes.size == allContentTypes.size
                        TextButton(
                            onClick = {
                                draft = draft.copy(
                                    allowedContentTypes = if (isAllSelected) emptySet() else allContentTypes.toSet(),
                                )
                            },
                        ) {
                            Text(
                                text = stringResource(if (isAllSelected) R.string.deselect_all else R.string.select_all),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    if (draft.allowedContentTypes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.space_content_types_required_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        allContentTypes.forEach { type ->
                            val selected = type in draft.allowedContentTypes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    draft = draft.copy(
                                        allowedContentTypes = draft.allowedContentTypes.toggled(type, !selected),
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(type.titleResId),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }
                }

                // Languages Section
                if (availableLanguages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.languages),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = if (draft.sourceLanguages.isEmpty()) {
                                            stringResource(R.string.all_languages)
                                        } else {
                                            draft.sourceLanguages.size.toString()
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (draft.sourceLanguages.isNotEmpty()) {
                                TextButton(onClick = { draft = draft.copy(sourceLanguages = emptySet()) }) {
                                    Text(
                                        text = stringResource(R.string.all_languages),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.space_languages_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            availableLanguages.forEach { language ->
                                val selected = language in draft.sourceLanguages
                                val label = Locale.forLanguageTag(language)
                                    .getDisplayName(Locale.getDefault())
                                    .ifBlank { language }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(
                                            sourceLanguages = draft.sourceLanguages.toggled(language, !selected),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }
                    }
                }

                // Source Types Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.source_types),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = if (draft.sourceKinds.isEmpty()) {
                                        stringResource(R.string.all_sources)
                                    } else {
                                        draft.sourceKinds.size.toString()
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (draft.sourceKinds.isNotEmpty()) {
                            TextButton(onClick = { draft = draft.copy(sourceKinds = emptySet()) }) {
                                Text(
                                    text = stringResource(R.string.all_sources),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.space_source_types_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SourceType.entries.forEach { type ->
                            val selected = type in draft.sourceKinds
                            val label = type.name.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    draft = draft.copy(
                                        sourceKinds = draft.sourceKinds.toggled(type, !selected),
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                enabled = draft.title?.isNotBlank() == true && draft.allowedContentTypes.isNotEmpty(),
                onClick = {
                    onConfirm(
                        draft.copy(
                            title = draft.title?.trim(),
                            kind = draft.allowedContentTypes.primarySpaceKind(),
                        ),
                    )
                },
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
    )
}

private fun SpaceContext.iconRes(): Int = when {
    id == BuiltInSpaces.Novel || kind == SpaceKind.NOVEL -> R.drawable.ic_content_novel
    id == BuiltInSpaces.Anime || kind == SpaceKind.ANIME -> R.drawable.ic_content_video
    kind == SpaceKind.ALL -> R.drawable.ic_filter_content_type
    else -> R.drawable.ic_content_manga
}

private fun SpaceContext.iconTone(): SettingsIconTone = when {
    id == BuiltInSpaces.Manga -> SettingsIconTone.PRIMARY
    id == BuiltInSpaces.Novel -> SettingsIconTone.SECONDARY
    id == BuiltInSpaces.Anime -> SettingsIconTone.TERTIARY
    kind == SpaceKind.NOVEL -> SettingsIconTone.SECONDARY_VARIANT
    kind == SpaceKind.ANIME -> SettingsIconTone.TERTIARY_VARIANT
    else -> SettingsIconTone.PRIMARY_VARIANT
}

@Composable
private fun buildSpaceSummary(space: SpaceContext): String {
    if (space.isBuiltIn) {
        return stringResource(R.string.built_in_space)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val parts = mutableListOf<String>()

    val types = space.allowedContentTypes
    if (types.size in 1..2) {
        parts.add(types.joinToString(", ") { context.getString(it.titleResId) })
    } else {
        parts.add("${types.size} " + context.getString(R.string.content_types))
    }

    if (space.sourceLanguages.isEmpty()) {
        parts.add(context.getString(R.string.all_languages))
    } else {
        parts.add("${space.sourceLanguages.size} " + context.getString(R.string.languages))
    }

    if (space.sourceKinds.isEmpty()) {
        parts.add(context.getString(R.string.all_sources))
    } else {
        parts.add("${space.sourceKinds.size} " + context.getString(R.string.source_types))
    }

    return parts.joinToString(" · ")
}

private fun emptyCustomSpace() = SpaceContext(
    id = SpaceId("custom:draft"),
    kind = SpaceKind.ALL,
    allowedContentTypes = emptySet(),
    title = "",
    isBuiltIn = false,
)

private fun SpaceKind.defaultTitleRes() = when (this) {
    SpaceKind.MANGA -> R.string.space_manga
    SpaceKind.NOVEL -> R.string.space_novel
    SpaceKind.ANIME -> R.string.space_anime
    SpaceKind.ALL -> R.string.all
}

private fun <T> Set<T>.toggled(value: T, enabled: Boolean): Set<T> = if (enabled) this + value else this - value
