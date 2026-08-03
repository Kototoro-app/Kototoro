package org.skepsun.kototoro.filter.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroRangeSlider
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.model.titleRes
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.model.YEAR_MIN
import org.skepsun.kototoro.parsers.model.YEAR_UNKNOWN
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistActivity
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistStatus
import java.util.Locale
import java.util.TreeSet

@Composable
fun FilterSheetRoute(
    filter: FilterCoordinator,
    isEmbedded: Boolean,
    onDismiss: () -> Unit,
    onOpenTagCatalog: (groupTitle: String?, excludeMode: Boolean) -> Unit,
) {
    val snapshot by filter.observe().collectAsStateWithLifecycle(initialValue = filter.snapshot())
    val sortOrderProperty by filter.sortOrder.collectAsStateWithLifecycle()
    val savedFiltersProperty by filter.savedFilters.collectAsStateWithLifecycle()
    val localeProperty by filter.locale.collectAsStateWithLifecycle()
    val originalLocaleProperty by filter.originalLocale.collectAsStateWithLifecycle()
    val tagsProperty by filter.tags.collectAsStateWithLifecycle()
    val tagsExcludedProperty by filter.tagsExcluded.collectAsStateWithLifecycle()
    val authorsProperty by filter.authors.collectAsStateWithLifecycle()
    val statesProperty by filter.states.collectAsStateWithLifecycle()
    val contentTypesProperty by filter.contentTypes.collectAsStateWithLifecycle()
    val contentRatingProperty by filter.contentRating.collectAsStateWithLifecycle()
    val demographicsProperty by filter.demographics.collectAsStateWithLifecycle()
    val yearProperty by filter.year.collectAsStateWithLifecycle()
    val yearRangeProperty by filter.yearRange.collectAsStateWithLifecycle()
    val globalTagBlacklist by filter.globalTagBlacklist.collectAsStateWithLifecycle()
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    var pendingRenameFilter by remember { mutableStateOf<PersistableFilter?>(null) }
    var pendingOverwriteName by remember { mutableStateOf<String?>(null) }
    var pendingTextInputTag by remember { mutableStateOf<ContentTag?>(null) }
    val context = LocalContext.current
    val resources = context.resources

    LaunchedEffect(Unit) {
        savedFiltersProperty.availableItems
            .filter { it.autoEnabled && it !in savedFiltersProperty.selectedItems }
            .forEach { filter.toggleSavedFilter(it) }
    }

    FilterSheetContent(
        sourceName = filter.mangaSource.name,
        sortOrderProperty = sortOrderProperty,
        savedFiltersProperty = savedFiltersProperty,
        localeProperty = localeProperty,
        originalLocaleProperty = originalLocaleProperty,
        tagsProperty = tagsProperty,
        tagsExcludedProperty = tagsExcludedProperty,
        authorsProperty = authorsProperty,
        statesProperty = statesProperty,
        contentTypesProperty = contentTypesProperty,
        contentRatingProperty = contentRatingProperty,
        demographicsProperty = demographicsProperty,
        yearProperty = yearProperty,
        yearRangeProperty = yearRangeProperty,
        blacklistedTagCount = globalTagBlacklist.size,
        isSaveEnabled = snapshot.listFilter.isNotEmpty() && savedFiltersProperty.selectedItems.isEmpty(),
        isEmbedded = isEmbedded,
        onDismiss = onDismiss,
        onReset = filter::reset,
        onSave = { pendingSaveName = "" },
        onSortOrderChange = filter::setSortOrder,
        onLocaleChange = filter::setLocale,
        onOriginalLocaleChange = filter::setOriginalLocale,
        onAuthorChange = filter::setAuthor,
        onToggleState = filter::toggleState,
        onToggleContentType = filter::toggleContentType,
        onToggleContentRating = filter::toggleContentRating,
        onToggleDemographic = filter::toggleDemographic,
        onToggleTag = { tag, selected, excludeMode ->
            if (filter.isTextInputTag(tag)) {
                pendingTextInputTag = tag
            } else if (excludeMode) {
                filter.toggleTagExclude(tag, selected)
            } else {
                filter.toggleTag(tag, selected)
            }
        },
        onSetYear = filter::setYear,
        onSetYearRange = filter::setYearRange,
        onToggleSavedFilter = filter::toggleSavedFilter,
        onRenameSavedFilter = { pendingRenameFilter = it },
        onDeleteSavedFilter = { filter.deleteSavedFilter(it.id) },
        onSetSavedFilterAutoEnabled = { id, enabled -> filter.setSavedFilterAutoEnabled(id, enabled) },
        onTextInputTagClick = { pendingTextInputTag = it },
        onOpenTagCatalog = onOpenTagCatalog,
        onOpenGlobalTagBlacklist = {
            onDismiss()
            context.startActivity(GlobalTagBlacklistActivity.newIntent(context))
        },
        resolveSortOrderLabel = { sourceName, order ->
            resolveSortOrderLabel(sourceName, order, context::getString)
        },
        resolveErrorMessage = { error -> error?.getDisplayMessage(resources) },
        resolveLocaleLabel = { locale -> locale.getDisplayName(context) },
        textInputValue = filter::getTextInputValue,
        textInputLabel = filter::getTextInputLabel,
    )

    pendingSaveName?.let { initialName ->
        val existingNames = remember(savedFiltersProperty.availableItems) {
            savedFiltersProperty.availableItems.mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
        }
        FilterNameInputDialog(
            title = context.getString(R.string.save_filter),
            initialValue = initialName,
            existingNames = existingNames,
            rejectExistingName = false,
            confirmText = context.getString(R.string.save),
            onDismiss = { pendingSaveName = null },
            onConfirm = { name ->
                pendingSaveName = null
                if (name in existingNames) {
                    pendingOverwriteName = name
                } else {
                    filter.saveCurrentFilter(name)
                }
            },
        )
    }

    pendingRenameFilter?.let { preset ->
        val existingNames = remember(savedFiltersProperty.availableItems, preset.name) {
            savedFiltersProperty.availableItems
                .mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
                .apply { remove(preset.name) }
        }
        FilterNameInputDialog(
            title = context.getString(R.string.rename),
            initialValue = preset.name,
            existingNames = existingNames,
            rejectExistingName = true,
            confirmText = context.getString(R.string.save),
            onDismiss = { pendingRenameFilter = null },
            onConfirm = { name ->
                pendingRenameFilter = null
                filter.renameSavedFilter(preset.id, name)
            },
        )
    }

    pendingOverwriteName?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteName = null },
            title = { Text(context.getString(R.string.save_filter)) },
            text = { Text(context.getString(R.string.filter_overwrite_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        filter.saveCurrentFilter(name)
                    },
                ) {
                    Text(context.getString(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        pendingSaveName = name
                    },
                ) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
    }

    pendingTextInputTag?.let { tag ->
        TextInputTagDialog(
            title = filter.getTextInputLabel(tag),
            initialValue = filter.getTextInputValue(tag).orEmpty(),
            onDismiss = { pendingTextInputTag = null },
            onClear = {
                pendingTextInputTag = null
                filter.setTextInputValue(tag, "")
            },
            onConfirm = { value ->
                pendingTextInputTag = null
                filter.setTextInputValue(tag, value)
            },
        )
    }
}

@Composable
private fun FilterNameInputDialog(
    title: String,
    initialValue: String,
    existingNames: Set<String>,
    rejectExistingName: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val hasError = trimmed.isEmpty() || (rejectExistingName && trimmed in existingNames)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = value,
                    onValueChange = { value = it.take(MAX_TITLE_LENGTH) },
                    label = { Text(context.getString(R.string.filter_name)) },
                    singleLine = true,
                    isError = hasError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasError) {
                    Text(
                        text = context.getString(R.string.invalid_value_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !hasError,
                onClick = { onConfirm(trimmed) },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun TextInputTagDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) {
                Text(context.getString(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text(context.getString(R.string.clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(context.getString(android.R.string.cancel))
                }
            }
        },
    )
}

private fun resolveSortOrderLabel(
    sourceName: String,
    order: SortOrder,
    getString: (Int) -> String,
): String {
    if (sourceName.startsWith("TRACKING_BANGUMI_")) {
        return when (order) {
            SortOrder.RATING -> getString(R.string.sort_by_ranking)
            SortOrder.POPULARITY -> getString(R.string.sort_by_popularity_label)
            SortOrder.ADDED -> getString(R.string.sort_by_collection)
            SortOrder.NEWEST -> getString(R.string.sort_by_date_label)
            SortOrder.ALPHABETICAL -> getString(R.string.sort_by_name_label)
            else -> getString(order.titleRes)
        }
    }
    return getString(order.titleRes)
}

@Composable
private fun FilterSheetContent(
    sourceName: String,
    sortOrderProperty: FilterProperty<SortOrder>,
    savedFiltersProperty: FilterProperty<PersistableFilter>,
    localeProperty: FilterProperty<Locale?>,
    originalLocaleProperty: FilterProperty<Locale?>,
    tagsProperty: FilterProperty<UiTagGroup>,
    tagsExcludedProperty: FilterProperty<UiTagGroup>,
    authorsProperty: FilterProperty<String>,
    statesProperty: FilterProperty<ContentState>,
    contentTypesProperty: FilterProperty<ContentType>,
    contentRatingProperty: FilterProperty<ContentRating>,
    demographicsProperty: FilterProperty<Demographic>,
    yearProperty: FilterProperty<Int>,
    yearRangeProperty: FilterProperty<Int>,
    blacklistedTagCount: Int,
    isSaveEnabled: Boolean,
    isEmbedded: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onLocaleChange: (Locale?) -> Unit,
    onOriginalLocaleChange: (Locale?) -> Unit,
    onAuthorChange: (String?) -> Unit,
    onToggleState: (ContentState, Boolean) -> Unit,
    onToggleContentType: (ContentType, Boolean) -> Unit,
    onToggleContentRating: (ContentRating, Boolean) -> Unit,
    onToggleDemographic: (Demographic, Boolean) -> Unit,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onSetYear: (Int) -> Unit,
    onSetYearRange: (Int, Int) -> Unit,
    onToggleSavedFilter: (PersistableFilter) -> Unit,
    onRenameSavedFilter: (PersistableFilter) -> Unit,
    onDeleteSavedFilter: (PersistableFilter) -> Unit,
    onSetSavedFilterAutoEnabled: (Int, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
    onOpenGlobalTagBlacklist: () -> Unit,
    resolveSortOrderLabel: (String, SortOrder) -> String,
    resolveErrorMessage: (Throwable?) -> String?,
    resolveLocaleLabel: (Locale?) -> String,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
) {
    val scrollState = rememberScrollState()
    var sortExpanded by remember { mutableStateOf(false) }
    var authorInput by remember(authorsProperty.selectedItems) {
        mutableStateOf(authorsProperty.selectedItems.firstOrNull().orEmpty())
    }
    LaunchedEffect(authorsProperty.selectedItems) {
        authorInput = authorsProperty.selectedItems.firstOrNull().orEmpty()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        FilterSheetHeader(
            title = LocalContext.current.getString(R.string.filter),
            isEmbedded = isEmbedded,
            onDismiss = onDismiss,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlobalTagBlacklistStatus(
                blacklistedTagCount = blacklistedTagCount,
                onClick = onOpenGlobalTagBlacklist,
            )

            FilterSection(
                title = LocalContext.current.getString(R.string.sort_order),
                errorMessage = resolveErrorMessage(sortOrderProperty.error),
                loading = sortOrderProperty.isLoading,
                visible = !sortOrderProperty.isEmpty() || sortOrderProperty.isLoading || sortOrderProperty.error != null,
            ) {
                SortOrderSection(
                    sourceName = sourceName,
                    property = sortOrderProperty,
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = it },
                    onSortOrderChange = onSortOrderChange,
                    resolveSortOrderLabel = resolveSortOrderLabel,
                )
            }

            TagGroupsSection(
                title = LocalContext.current.getString(R.string.genres),
                property = tagsProperty,
                excludeMode = false,
                resolveErrorMessage = resolveErrorMessage,
                textInputValue = textInputValue,
                textInputLabel = textInputLabel,
                onToggleTag = onToggleTag,
                onTextInputTagClick = onTextInputTagClick,
                onOpenTagCatalog = onOpenTagCatalog,
            )

            TagGroupsSection(
                title = LocalContext.current.getString(R.string.genres_exclude),
                property = tagsExcludedProperty,
                excludeMode = true,
                resolveErrorMessage = resolveErrorMessage,
                textInputValue = textInputValue,
                textInputLabel = textInputLabel,
                onToggleTag = onToggleTag,
                onTextInputTagClick = onTextInputTagClick,
                onOpenTagCatalog = onOpenTagCatalog,
            )

            SavedFiltersSection(
                property = savedFiltersProperty,
                onToggleSavedFilter = onToggleSavedFilter,
                onRenameSavedFilter = onRenameSavedFilter,
                onDeleteSavedFilter = onDeleteSavedFilter,
                onSetSavedFilterAutoEnabled = onSetSavedFilterAutoEnabled,
                resolveErrorMessage = resolveErrorMessage,
            )

            LocaleSection(
                title = LocalContext.current.getString(R.string.language),
                property = localeProperty,
                onChange = onLocaleChange,
                resolveErrorMessage = resolveErrorMessage,
                resolveLocaleLabel = resolveLocaleLabel,
            )

            LocaleSection(
                title = LocalContext.current.getString(R.string.original_language),
                property = originalLocaleProperty,
                onChange = onOriginalLocaleChange,
                resolveErrorMessage = resolveErrorMessage,
                resolveLocaleLabel = resolveLocaleLabel,
            )

            AuthorsSection(
                property = authorsProperty,
                authorInput = authorInput,
                onAuthorInputChange = { value ->
                    authorInput = value
                    onAuthorChange(value.trim().ifBlank { null })
                },
                onAuthorSelect = {
                    authorInput = it.orEmpty()
                    onAuthorChange(it)
                },
                resolveErrorMessage = resolveErrorMessage,
            )

            MultiSelectSection(
                title = LocalContext.current.getString(R.string.type),
                property = contentTypesProperty,
                itemLabel = { LocalContext.current.getString(it.titleResId) },
                onToggle = onToggleContentType,
                resolveErrorMessage = resolveErrorMessage,
            )

            MultiSelectSection(
                title = LocalContext.current.getString(R.string.state),
                property = statesProperty,
                itemLabel = { LocalContext.current.getString(it.titleResId) },
                onToggle = onToggleState,
                resolveErrorMessage = resolveErrorMessage,
            )

            MultiSelectSection(
                title = LocalContext.current.getString(R.string.content_rating),
                property = contentRatingProperty,
                itemLabel = { LocalContext.current.getString(it.titleResId) },
                onToggle = onToggleContentRating,
                resolveErrorMessage = resolveErrorMessage,
            )

            MultiSelectSection(
                title = LocalContext.current.getString(R.string.demographics),
                property = demographicsProperty,
                itemLabel = { LocalContext.current.getString(it.titleResId) },
                onToggle = onToggleDemographic,
                resolveErrorMessage = resolveErrorMessage,
            )

            YearSection(
                property = yearProperty,
                onSetYear = onSetYear,
                resolveErrorMessage = resolveErrorMessage,
            )

            YearRangeSection(
                property = yearRangeProperty,
                onSetYearRange = onSetYearRange,
                resolveErrorMessage = resolveErrorMessage,
            )
        }

        FilterSheetActions(
            isEmbedded = isEmbedded,
            isSaveEnabled = isSaveEnabled,
            onSave = onSave,
            onReset = onReset,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SortOrderSection(
    sourceName: String,
    property: FilterProperty<SortOrder>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    resolveSortOrderLabel: (String, SortOrder) -> String,
) {
    val selected = property.selectedItems.firstOrNull()
    val selectedLabel = selected?.let { resolveSortOrderLabel(sourceName, it) }.orEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    property.availableItems.forEachIndexed { index, item ->
                        val isSelected = item == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSortOrderChange(item)
                                    onExpandedChange(false)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = resolveSortOrderLabel(sourceName, item),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        if (index != property.availableItems.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheetHeader(
    title: String,
    isEmbedded: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (!isEmbedded) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSheetActions(
    isEmbedded: Boolean,
    isSaveEnabled: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = isSaveEnabled,
            ) {
                Text(text = LocalContext.current.getString(R.string.save))
            }
            OutlinedButton(onClick = onReset) {
                Text(text = LocalContext.current.getString(R.string.reset_filter))
            }
            if (!isEmbedded) {
                TextButton(onClick = onDismiss) {
                    Text(text = LocalContext.current.getString(android.R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun SavedFiltersSection(
    property: FilterProperty<PersistableFilter>,
    onToggleSavedFilter: (PersistableFilter) -> Unit,
    onRenameSavedFilter: (PersistableFilter) -> Unit,
    onDeleteSavedFilter: (PersistableFilter) -> Unit,
    onSetSavedFilterAutoEnabled: (Int, Boolean) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
) {
    var menuPreset by remember { mutableStateOf<PersistableFilter?>(null) }
    FilterSection(
        title = LocalContext.current.getString(R.string.saved_filters),
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        CompactFilterChipFlow {
            property.availableItems.forEach { preset ->
                val selected = preset in property.selectedItems
                Box {
                    CompactFilterChip(
                        selected = selected,
                        onClick = { onToggleSavedFilter(preset) },
                        label = {
                            Text(
                                text = preset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { menuPreset = preset },
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = menuPreset == preset,
                        onDismissRequest = { menuPreset = null },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (preset.autoEnabled) {
                                        LocalContext.current.getString(R.string.disable_auto_apply)
                                    } else {
                                        LocalContext.current.getString(R.string.enable_auto_apply)
                                    },
                                )
                            },
                            onClick = {
                                menuPreset = null
                                onSetSavedFilterAutoEnabled(preset.id, !preset.autoEnabled)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(LocalContext.current.getString(R.string.rename)) },
                            onClick = {
                                menuPreset = null
                                onRenameSavedFilter(preset)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(LocalContext.current.getString(R.string.delete)) },
                            onClick = {
                                menuPreset = null
                                onDeleteSavedFilter(preset)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocaleSection(
    title: String,
    property: FilterProperty<Locale?>,
    onChange: (Locale?) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
    resolveLocaleLabel: (Locale?) -> String,
) {
    FilterSection(
        title = title,
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        CompactFilterChipFlow {
            property.availableItems.forEach { locale ->
                val selected = locale in property.selectedItems
                CompactFilterChip(
                    selected = selected,
                    onClick = { onChange(if (selected) null else locale) },
                    label = {
                        Text(
                            text = resolveLocaleLabel(locale),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AuthorsSection(
    property: FilterProperty<String>,
    authorInput: String,
    onAuthorInputChange: (String) -> Unit,
    onAuthorSelect: (String?) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
) {
    FilterSection(
        title = LocalContext.current.getString(R.string.author),
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        OutlinedTextField(
            value = authorInput,
            onValueChange = onAuthorInputChange,
            label = { Text(LocalContext.current.getString(R.string.author)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
        )
        if (property.availableItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            CompactFilterChipFlow {
                property.availableItems.take(24).forEach { author ->
                    val selected = author in property.selectedItems
                    CompactFilterChip(
                        selected = selected,
                        onClick = { onAuthorSelect(if (selected) null else author) },
                        label = {
                            Text(
                                text = author,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> MultiSelectSection(
    title: String,
    property: FilterProperty<T>,
    itemLabel: @Composable (T) -> String,
    onToggle: (T, Boolean) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
) {
    FilterSection(
        title = title,
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        CompactFilterChipFlow {
            property.availableItems.forEach { item ->
                val selected = item in property.selectedItems
                CompactFilterChip(
                    selected = selected,
                    onClick = { onToggle(item, !selected) },
                    label = { Text(itemLabel(item)) },
                )
            }
        }
    }
}

@Composable
private fun YearSection(
    property: FilterProperty<Int>,
    onSetYear: (Int) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
) {
    FilterSection(
        title = LocalContext.current.getString(R.string.year),
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        val minYear = property.availableItems.firstOrNull() ?: YEAR_MIN
        val maxYear = property.availableItems.lastOrNull() ?: minYear
        var sliderValue by remember(property.selectedItems) {
            mutableStateOf((property.selectedItems.firstOrNull() ?: YEAR_UNKNOWN).let { value ->
                if (value == YEAR_UNKNOWN) minYear.toFloat() else value.toFloat()
            })
        }
        LaunchedEffect(property.selectedItems, minYear) {
            sliderValue = (property.selectedItems.firstOrNull() ?: YEAR_UNKNOWN).let { value ->
                if (value == YEAR_UNKNOWN) minYear.toFloat() else value.toFloat()
            }
        }
        Text(
            text = if (sliderValue.toInt() <= minYear) {
                LocalContext.current.getString(R.string.any)
            } else {
                sliderValue.toInt().toString()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KototoroSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = minYear.toFloat()..maxYear.toFloat(),
            steps = (maxYear - minYear - 1).coerceAtLeast(0),
            onValueChangeFinished = {
                onSetYear(
                    if (sliderValue.toInt() <= minYear) YEAR_UNKNOWN else sliderValue.toInt(),
                )
            },
        )
    }
}

@Composable
private fun YearRangeSection(
    property: FilterProperty<Int>,
    onSetYearRange: (Int, Int) -> Unit,
    resolveErrorMessage: (Throwable?) -> String?,
) {
    FilterSection(
        title = LocalContext.current.getString(R.string.years),
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = !property.isEmpty() || property.isLoading || property.error != null,
    ) {
        val minYear = property.availableItems.firstOrNull() ?: YEAR_MIN
        val maxYear = property.availableItems.lastOrNull() ?: minYear
        val selectedFrom = property.selectedItems.minOrNull() ?: minYear
        val selectedTo = property.selectedItems.maxOrNull() ?: maxYear
        var sliderValues by remember(property.selectedItems) {
            mutableStateOf(selectedFrom.toFloat() to selectedTo.toFloat())
        }
        LaunchedEffect(property.selectedItems) {
            sliderValues = selectedFrom.toFloat() to selectedTo.toFloat()
        }
        val fromLabel = if (sliderValues.first.toInt() <= minYear) {
            LocalContext.current.getString(R.string.any)
        } else {
            sliderValues.first.toInt().toString()
        }
        val toLabel = if (sliderValues.second.toInt() >= maxYear) {
            LocalContext.current.getString(R.string.any)
        } else {
            sliderValues.second.toInt().toString()
        }
        Text(
            text = "$fromLabel - $toLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KototoroRangeSlider(
            value = sliderValues.first..sliderValues.second,
            onValueChange = { range ->
                sliderValues = range.start to range.endInclusive
            },
            valueRange = minYear.toFloat()..maxYear.toFloat(),
            steps = (maxYear - minYear - 1).coerceAtLeast(0),
            onValueChangeFinished = {
                onSetYearRange(
                    if (sliderValues.first.toInt() <= minYear) YEAR_UNKNOWN else sliderValues.first.toInt(),
                    if (sliderValues.second.toInt() >= maxYear) YEAR_UNKNOWN else sliderValues.second.toInt(),
                )
            },
        )
    }
}

@Composable
private fun TagGroupsSection(
    title: String,
    property: FilterProperty<UiTagGroup>,
    excludeMode: Boolean,
    resolveErrorMessage: (Throwable?) -> String?,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val groups = property.availableItems.filter { it.tags.isNotEmpty() }
    FilterSection(
        title = title,
        errorMessage = resolveErrorMessage(property.error),
        loading = property.isLoading,
        visible = groups.isNotEmpty() || property.isLoading || property.error != null,
    ) {
        groups.forEach { group ->
            TagGroupBlock(
                group = group,
                excludeMode = excludeMode,
                textInputValue = textInputValue,
                textInputLabel = textInputLabel,
                onToggleTag = onToggleTag,
                onTextInputTagClick = onTextInputTagClick,
                onOpenTagCatalog = onOpenTagCatalog,
            )
        }
    }
}

@Composable
private fun TagGroupBlock(
    group: UiTagGroup,
    excludeMode: Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val sortedTags = remember(group) {
        val selectedKeys = group.selected.map { it.key }.toSet()
        val selectedTags = group.tags.filter { it.key in selectedKeys }
        val unselectedTags = group.tags.filterNot { it.key in selectedKeys }.sortedBy { it.title }
        (selectedTags + unselectedTags).distinctBy { it.key }
    }
    val visibleTags = remember(sortedTags) { sortedTags.take(12) }
    val canExpand = sortedTags.size > visibleTags.size

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (canExpand) {
                IconButton(
                    onClick = { onOpenTagCatalog(group.title, excludeMode) },
                    modifier = Modifier.size(18.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = LocalContext.current.getString(R.string.show_more),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        CompactFilterChipFlow {
            visibleTags.forEach { tag ->
                val value = textInputValue(tag)
                val isTextInput = value != null || textInputLabel(tag) != tag.title || tag.key.startsWith("text:")
                val selected = if (isTextInput) {
                    !value.isNullOrBlank()
                } else {
                    tag in group.selected
                }
                CompactFilterChip(
                    selected = selected,
                    onClick = {
                        if (isTextInput) {
                            onTextInputTagClick(tag)
                        } else {
                            onToggleTag(tag, !selected, excludeMode)
                        }
                    },
                    label = {
                        Text(
                            text = if (isTextInput && !value.isNullOrBlank()) {
                                "${textInputLabel(tag)}: $value"
                            } else if (isTextInput) {
                                textInputLabel(tag)
                            } else {
                                tag.title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = if (isTextInput) {
                        {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    errorMessage: String?,
    loading: Boolean,
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    if (!visible) {
        return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                else -> content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactFilterChipFlow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        label = {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                label()
            }
        },
        trailingIcon = trailingIcon,
    )
}
