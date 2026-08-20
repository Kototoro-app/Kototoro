package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.util.AlphanumComparator

import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistStatus
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Locale
import java.util.TreeSet

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchFilterPanel(
    sourceName: String,
    sortOrders: List<SortOrder>,
    selectedSortOrder: SortOrder?,
    tagGroups: List<UiTagGroup>,
    excludedTagGroups: List<UiTagGroup>,
    contentTypes: List<ContentType>,
    selectedContentTypes: Set<ContentType>,
    states: List<ContentState>,
    selectedStates: Set<ContentState>,
    locales: List<Locale?>,
    selectedLocale: Locale?,
    authors: List<String>,
    selectedAuthor: String?,
    blacklistedTagCount: Int,
    onOpenGlobalTagBlacklist: () -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onToggleContentType: (ContentType, Boolean) -> Unit,
    onToggleState: (ContentState, Boolean) -> Unit,
    onLocaleChange: (Locale?) -> Unit,
    onAuthorChange: (String?) -> Unit,
    onReset: () -> Unit,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onSetTextInputValue: (ContentTag, String) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    savedFilters: FilterProperty<PersistableFilter> = FilterProperty.EMPTY,
    isSaveEnabled: Boolean = false,
    onToggleSavedFilter: (PersistableFilter) -> Unit = {},
    onSaveFilter: (String) -> Unit = {},
    onRenameSavedFilter: (Int, String) -> Unit = { _, _ -> },
    onDeleteSavedFilter: (Int) -> Unit = {},
    onSetSavedFilterAutoEnabled: (Int, Boolean) -> Unit = { _, _ -> },
) {
    val scrollState = rememberScrollState()
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    var textInputDialog by remember { mutableStateOf<ContentTag?>(null) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteName by remember { mutableStateOf<String?>(null) }
    var pendingRenameFilter by remember { mutableStateOf<PersistableFilter?>(null) }
    var savedFilterMenuPreset by remember { mutableStateOf<PersistableFilter?>(null) }

    Column(
        modifier = modifier
            .then(if (fillAvailableHeight) Modifier.fillMaxHeight() else Modifier)
            .verticalScroll(scrollState)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { pendingSaveName = "" },
                    enabled = isSaveEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                    ),
                ) {
                    Text(stringResource(R.string.save))
                }
                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                    ),
                ) {
                    Text(stringResource(R.string.reset_filter))
                }
            }
        }

        GlobalTagBlacklistStatus(
            blacklistedTagCount = blacklistedTagCount,
            onClick = onOpenGlobalTagBlacklist,
        )

        FilterSection(title = stringResource(R.string.sort_order)) {
            SortOrderFilterSection(
                sourceName = sourceName,
                sortOrders = sortOrders,
                selectedSortOrder = selectedSortOrder,
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it },
                onSortOrderChange = onSortOrderChange,
            )
        }

        if (contentTypes.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.type)) {
                FilterChipFlow {
                    contentTypes.forEach { type ->
                        val isSelected = type in selectedContentTypes
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onToggleContentType(type, !isSelected) },
                            label = { Text(stringResource(type.titleResId)) },
                        )
                    }
                }
            }
        }

        if (states.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.state)) {
                FilterChipFlow {
                    states.forEach { state -> 
                        val isSelected = state in selectedStates
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onToggleState(state, !isSelected) },
                            label = { Text(stringResource(state.titleResId)) },
                        )
                    }
                }
            }
        }

        if (locales.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.language)) {
                FilterChipFlow {
                    locales.forEach { locale ->
                        val isSelected = locale == selectedLocale
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onLocaleChange(if (isSelected) null else locale) },
                            label = {
                                Text(
                                    if (locale == null) {
                                        stringResource(R.string.all)
                                    } else {
                                        locale.getDisplayName(locale).ifBlank { locale.toLanguageTag() }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        if (authors.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.author)) {
                OutlinedTextField(
                    value = selectedAuthor.orEmpty(),
                    onValueChange = { onAuthorChange(it.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.author)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilterChipFlow {
                    authors.take(12).forEach { author ->
                        val isSelected = author == selectedAuthor
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onAuthorChange(if (isSelected) null else author) },
                            label = { Text(author) },
                        )
                    }
                }
            }
        }

        TagGroupsSection(
            title = stringResource(R.string.genres),
            tagGroups = tagGroups,
            excludeMode = false,
            isTextInputTag = isTextInputTag,
            textInputValue = textInputValue,
            textInputLabel = textInputLabel,
            onToggleTag = onToggleTag,
            onTextInputTagClick = { tag -> textInputDialog = tag },
            onOpenTagCatalog = onOpenTagCatalog,
        )

        if (excludedTagGroups.any { it.tags.isNotEmpty() }) {
            TagGroupsSection(
                title = stringResource(R.string.genres_exclude),
                tagGroups = excludedTagGroups,
                excludeMode = true,
                isTextInputTag = isTextInputTag,
                textInputValue = textInputValue,
                textInputLabel = textInputLabel,
                onToggleTag = onToggleTag,
                onTextInputTagClick = { tag -> textInputDialog = tag },
                onOpenTagCatalog = onOpenTagCatalog,
            )
        }

        if (!savedFilters.isEmpty() || savedFilters.isLoading) {
            FilterSection(title = stringResource(R.string.saved_filters)) {
                FilterChipFlow {
                    savedFilters.availableItems.forEach { preset ->
                        val selected = preset in savedFilters.selectedItems
                        Box {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                                FilterChip(
                                    selected = selected,
                                    onClick = { onToggleSavedFilter(preset) },
                                    modifier = Modifier.heightIn(min = 24.dp),
                                    label = {
                                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                            Text(
                                                text = preset.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { savedFilterMenuPreset = preset },
                                            modifier = Modifier.size(18.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.34f),
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                                        } else if (preset.autoEnabled) {
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.58f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                                        },
                                    ),
                                )
                            }
                            DropdownMenu(
                                expanded = savedFilterMenuPreset == preset,
                                onDismissRequest = { savedFilterMenuPreset = null },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (preset.autoEnabled) {
                                                stringResource(R.string.disable_auto_apply)
                                            } else {
                                                stringResource(R.string.enable_auto_apply)
                                            },
                                        )
                                    },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        onSetSavedFilterAutoEnabled(preset.id, !preset.autoEnabled)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        pendingRenameFilter = preset
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        onDeleteSavedFilter(preset.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    textInputDialog?.let { tag ->
        TextInputTagDialog(
            title = textInputLabel(tag),
            initialValue = textInputValue(tag).orEmpty(),
            onConfirm = { value ->
                onSetTextInputValue(tag, value.trim())
                textInputDialog = null
            },
            onClear = {
                onSetTextInputValue(tag, "")
                textInputDialog = null
            },
            onDismissRequest = { textInputDialog = null },
        )
    }

    pendingSaveName?.let { initialName ->
        val existingNames = remember(savedFilters.availableItems) {
            savedFilters.availableItems.mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
        }
        SaveFilterNameDialog(
            initialValue = initialName,
            existingNames = existingNames,
            onDismiss = { pendingSaveName = null },
            onConfirm = { name ->
                pendingSaveName = null
                if (name in existingNames) {
                    pendingOverwriteName = name
                } else {
                    onSaveFilter(name)
                }
            },
        )
    }

    pendingOverwriteName?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteName = null },
            title = { Text(stringResource(R.string.save_filter)) },
            text = { Text(stringResource(R.string.filter_overwrite_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        onSaveFilter(name)
                    },
                ) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        pendingSaveName = name
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingRenameFilter?.let { preset ->
        val existingNames = remember(savedFilters.availableItems, preset.name) {
            savedFilters.availableItems
                .mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
                .apply { remove(preset.name) }
        }
        SaveFilterNameDialog(
            initialValue = preset.name,
            existingNames = existingNames,
            rejectExistingName = true,
            onDismiss = { pendingRenameFilter = null },
            onConfirm = { name ->
                pendingRenameFilter = null
                onRenameSavedFilter(preset.id, name)
            },
        )
    }
}

