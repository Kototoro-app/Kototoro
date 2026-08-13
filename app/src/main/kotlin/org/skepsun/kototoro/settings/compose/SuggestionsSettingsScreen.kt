package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.parsers.model.ContentType

data class SuggestionSourceOption(
    val id: String,
    val title: String,
    val contentType: ContentType,
    val sourceType: SourceType,
)

@Composable
fun SuggestionsSettingsScreen(
    settings: AppSettings,
    excludeTags: String,
    preferredTags: String,
    sourceOptions: List<SuggestionSourceOption>,
    preferredSources: Set<String>,
    excludedSources: Set<String>,
    onExcludeTagsChanged: (String) -> Unit,
    onPreferredTagsChanged: (String) -> Unit,
    onPreferredSourcesChanged: (Set<String>) -> Unit,
    onExcludedSourcesChanged: (Set<String>) -> Unit,
) {
    val isEnabled by settings.observeAsState(AppSettings.KEY_SUGGESTIONS) { isSuggestionsEnabled }
    val isWifiOnly by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY) { isSuggestionsWiFiOnly }
    val includeDisabledSources by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_DISABLED_SOURCES) {
        isSuggestionsIncludeDisabledSources
    }
    val notificationsEnabled by settings.observeAsState(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS) {
        isSuggestionsNotificationAvailable
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceSection(
                title = stringResource(R.string.suggestions),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.suggestions_enable),
                    iconRes = R.drawable.ic_suggestion,
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        settings.isSuggestionsEnabled = checked
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.only_using_wifi),
                    iconRes = R.drawable.ic_wifi,
                    summary = stringResource(R.string.suggestions_wifi_only_summary),
                    checked = isWifiOnly,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.include_disabled_sources),
                    iconRes = R.drawable.ic_manga_source,
                    summary = stringResource(R.string.suggestions_disabled_sources_summary),
                    checked = includeDisabledSources,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_DISABLED_SOURCES, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.notifications_enable),
                    iconRes = R.drawable.ic_notification,
                    summary = stringResource(R.string.suggestions_notifications_summary),
                    checked = notificationsEnabled,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit().putBoolean(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS, checked).apply()
                    },
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.suggestions_excluded_genres),
                    iconRes = R.drawable.ic_tag,
                    summary = excludeTags.ifEmpty { stringResource(R.string.suggestions_excluded_genres_summary) },
                    value = excludeTags,
                    enabled = isEnabled,
                    onValueChange = onExcludeTagsChanged,
                )
                SettingsSectionDivider()
                SettingsTextInputPreference(
                    title = stringResource(R.string.suggestions_preferred_genres),
                    iconRes = R.drawable.ic_star_small,
                    summary = preferredTags.ifEmpty { stringResource(R.string.suggestions_preferred_genres_summary) },
                    value = preferredTags,
                    enabled = isEnabled,
                    onValueChange = onPreferredTagsChanged,
                )
                SettingsSectionDivider()
                SuggestionSourcesPreference(
                    title = stringResource(R.string.suggestions_preferred_sources),
                    iconRes = R.drawable.ic_star_small,
                    emptySummary = stringResource(R.string.suggestions_preferred_sources_summary),
                    options = sourceOptions,
                    selectedIds = preferredSources,
                    enabled = isEnabled,
                    onSelectedIdsChanged = onPreferredSourcesChanged,
                )
                SettingsSectionDivider()
                SuggestionSourcesPreference(
                    title = stringResource(R.string.suggestions_excluded_sources),
                    iconRes = R.drawable.ic_disable,
                    emptySummary = stringResource(R.string.suggestions_excluded_sources_summary),
                    options = sourceOptions,
                    selectedIds = excludedSources,
                    enabled = isEnabled,
                    onSelectedIdsChanged = onExcludedSourcesChanged,
                )
                SettingsSectionDivider()
                SettingsInfoPreference(
                    title = stringResource(R.string.suggestions_info),
                    iconRes = R.drawable.ic_info_outline,
                    summary = "",
                )
            }
        }
    }
}

@Composable
private fun SuggestionSourcesPreference(
    title: String,
    emptySummary: String,
    @androidx.annotation.DrawableRes iconRes: Int? = null,
    options: List<SuggestionSourceOption>,
    selectedIds: Set<String>,
    enabled: Boolean,
    onSelectedIdsChanged: (Set<String>) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedTitles = remember(options, selectedIds) {
        options.filter { it.id in selectedIds }.map { it.title }
    }
    val summary = if (selectedTitles.isEmpty()) {
        emptySummary
    } else {
        selectedTitles.take(3).joinToString().let { names ->
            if (selectedTitles.size > 3) "$names +${selectedTitles.size - 3}" else names
        }
    }
    SettingsActionPreference(
        title = title,
        summary = summary,
        iconRes = iconRes,
        enabled = enabled,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        SuggestionSourcesDialog(
            title = title,
            options = options,
            selectedIds = selectedIds,
            onDismiss = { showDialog = false },
            onConfirm = {
                onSelectedIdsChanged(it)
                showDialog = false
            },
        )
    }
}

@Composable
private fun SuggestionSourcesDialog(
    title: String,
    options: List<SuggestionSourceOption>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingIds by remember(selectedIds) { mutableStateOf(selectedIds) }
    var selectedContentTypes by remember { mutableStateOf(emptySet<ContentType>()) }
    var selectedSourceTypes by remember { mutableStateOf(emptySet<SourceType>()) }
    val availableContentTypes = remember(options) {
        options.map { it.contentType }.distinct().sortedBy { it.ordinal }
    }
    val availableSourceTypes = remember(options) {
        options.map { it.sourceType }.distinct().sortedBy { it.ordinal }
    }
    val visibleOptions = remember(options, query, selectedContentTypes, selectedSourceTypes) {
        filterSuggestionSourceOptions(options, query, selectedContentTypes, selectedSourceTypes)
    }
    SettingsAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                onClick = { onConfirm(pendingIds) },
            )
        },
        dismissButton = {
            Row {
                SettingsDialogActionButton(
                    text = stringResource(R.string.clear),
                    onClick = { pendingIds = emptySet() },
                )
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                )
            }
        },
    ) {
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search)) },
            )
            SuggestionSourceFilterRow(
                title = stringResource(R.string.content_types),
                allLabel = stringResource(R.string.all_content),
                options = availableContentTypes,
                selectedOptions = selectedContentTypes,
                optionKey = ContentType::name,
                optionLabel = { stringResource(it.titleResId) },
                onAllClick = { selectedContentTypes = emptySet() },
                onOptionClick = { type -> selectedContentTypes = selectedContentTypes.toggled(type) },
            )
            SuggestionSourceFilterRow(
                title = stringResource(R.string.source_types),
                allLabel = stringResource(R.string.all_sources),
                options = availableSourceTypes,
                selectedOptions = selectedSourceTypes,
                optionKey = SourceType::name,
                optionLabel = { stringResource(it.titleResId) },
                onAllClick = { selectedSourceTypes = emptySet() },
                onOptionClick = { type -> selectedSourceTypes = selectedSourceTypes.toggled(type) },
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(top = 8.dp),
            ) {
                items(visibleOptions, key = { it.id }) { option ->
                    val selected = option.id in pendingIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pendingIds = if (selected) pendingIds - option.id else pendingIds + option.id
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                pendingIds = if (selected) pendingIds - option.id else pendingIds + option.id
                            },
                        )
                        Text(
                            text = option.title,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SuggestionSourceFilterRow(
    title: String,
    allLabel: String,
    options: List<T>,
    selectedOptions: Set<T>,
    optionKey: (T) -> String,
    optionLabel: @Composable (T) -> String,
    onAllClick: () -> Unit,
    onOptionClick: (T) -> Unit,
) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "all") {
            FilterChip(
                selected = selectedOptions.isEmpty(),
                onClick = onAllClick,
                label = { Text(allLabel) },
            )
        }
        items(options, key = optionKey) { option ->
            FilterChip(
                selected = option in selectedOptions,
                onClick = { onOptionClick(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}

internal fun filterSuggestionSourceOptions(
    options: List<SuggestionSourceOption>,
    query: String,
    contentTypes: Set<ContentType>,
    sourceTypes: Set<SourceType>,
): List<SuggestionSourceOption> {
    val normalizedQuery = query.trim()
    return options.filter { option ->
        (contentTypes.isEmpty() || option.contentType in contentTypes) &&
            (sourceTypes.isEmpty() || option.sourceType in sourceTypes) &&
            (normalizedQuery.isEmpty() ||
                option.title.contains(normalizedQuery, ignoreCase = true) ||
                option.id.contains(normalizedQuery, ignoreCase = true))
    }
}

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

private val SourceType.titleResId: Int
    get() = when (this) {
        SourceType.NATIVE -> R.string.source_type_native
        SourceType.JSON_LEGADO -> R.string.source_type_legado
        SourceType.JSON_TVBOX -> R.string.source_type_tvbox
        SourceType.JSON_JS -> R.string.source_type_js
        SourceType.JSON_LNREADER -> R.string.source_type_lnreader
        SourceType.EXTERNAL -> R.string.external_source
        SourceType.MIHON -> R.string.source_type_mihon
        SourceType.ANIYOMI -> R.string.source_type_aniyomi
        SourceType.IREADER -> R.string.source_type_ireader
        SourceType.CLOUDSTREAM -> R.string.source_type_cloudstream
    }
