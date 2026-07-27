package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceKind

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
                horizontal = SettingsContentHorizontalPadding,
                vertical = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsPreferenceSection(title = stringResource(R.string.spaces), modifier = Modifier.fillMaxWidth()) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.spaces_enabled),
                        summary = stringResource(R.string.spaces_enabled_summary),
                        checked = state.spacesEnabled,
                        onCheckedChange = onSpacesEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.space_switcher_position),
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
            if (state.spacesEnabled) {
                item {
                    SettingsPreferenceSection(
                        title = stringResource(R.string.custom_spaces),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        definitions.spaces.forEachIndexed { index, space ->
                            if (index > 0) SettingsSectionDivider()
                            SpaceDefinitionRow(
                                space = space,
                                onEnabledChange = { onSave(space.copy(enabled = it)) },
                                onEdit = { editing = space },
                                onDelete = { deleting = space },
                                onMove = { onMove(space, it) },
                            )
                        }
                        if (definitions.spaces.isNotEmpty()) SettingsSectionDivider()
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
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = space.title ?: stringResource(space.kind.defaultTitleRes()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (space.isBuiltIn) {
                    stringResource(R.string.built_in_space)
                } else {
                    stringResource(
                        R.string.custom_space_rule_summary,
                        space.allowedContentTypes.size,
                        space.sourceLanguages.size,
                        space.sourceKinds.size,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!space.isBuiltIn) {
            IconButton(onClick = { onMove(-1) }) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
            }
            IconButton(onClick = { onMove(1) }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
            Switch(checked = space.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun SpaceEditorDialog(
    initial: SpaceContext,
    availableLanguages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (SpaceContext) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    SettingsAlertDialog(
        title = stringResource(
            if (initial.id.value == "custom:draft") R.string.add_custom_space else R.string.edit_custom_space,
        ),
        onDismissRequest = onDismiss,
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                item {
                    OutlinedTextField(
                        value = draft.title.orEmpty(),
                        onValueChange = { draft = draft.copy(title = it) },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                    RuleSectionTitle(stringResource(R.string.content_types))
                }
                items(ContentType.entries.filterNot { it == ContentType.OTHER }) { type ->
                    RuleCheckbox(
                        label = stringResource(type.titleResId),
                        checked = type in draft.allowedContentTypes,
                        onCheckedChange = { checked ->
                            draft = draft.copy(allowedContentTypes = draft.allowedContentTypes.toggled(type, checked))
                        },
                    )
                }
                item { RuleSectionTitle(stringResource(R.string.languages)) }
                items(availableLanguages.toList(), key = { it }) { language ->
                    RuleCheckbox(
                        label = Locale.forLanguageTag(language).getDisplayName(Locale.getDefault()).ifBlank { language },
                        checked = language in draft.sourceLanguages,
                        onCheckedChange = { checked ->
                            draft = draft.copy(sourceLanguages = draft.sourceLanguages.toggled(language, checked))
                        },
                    )
                }
                item { RuleSectionTitle(stringResource(R.string.source_types)) }
                items(SourceType.entries) { type ->
                    RuleCheckbox(
                        label = type.name.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() },
                        checked = type in draft.sourceKinds,
                        onCheckedChange = { checked ->
                            draft = draft.copy(sourceKinds = draft.sourceKinds.toggled(type, checked))
                        },
                    )
                }
            }
        },
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                enabled = draft.title?.isNotBlank() == true && draft.allowedContentTypes.isNotEmpty(),
                onClick = { onConfirm(draft) },
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

@Composable
private fun RuleSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun RuleCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun emptyCustomSpace() = SpaceContext(
    id = SpaceId("custom:draft"),
    kind = SpaceKind.MANGA,
    allowedContentTypes = emptySet(),
    title = "",
    isBuiltIn = false,
)

private fun SpaceKind.defaultTitleRes() = when (this) {
    SpaceKind.MANGA -> R.string.space_manga
    SpaceKind.NOVEL -> R.string.space_novel
    SpaceKind.ANIME -> R.string.space_anime
}

private fun <T> Set<T>.toggled(value: T, enabled: Boolean): Set<T> = if (enabled) this + value else this - value
