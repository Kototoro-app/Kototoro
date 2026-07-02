package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

sealed interface SourceSettingsRowUiState {
    val id: String
}

data class SourceSettingsSectionUiState(
    val id: String,
    val title: String,
    val rows: List<SourceSettingsRowUiState>,
)

data class SourceSettingsGroupLabelRowUiState(
    override val id: String,
    val text: String,
) : SourceSettingsRowUiState

data class SourceSettingsActionRowUiState(
    override val id: String,
    val title: String,
    val summary: String? = null,
    val enabled: Boolean = true,
    val showChevron: Boolean = true,
    val onClick: () -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsSwitchRowUiState(
    override val id: String,
    val title: String,
    val checked: Boolean,
    val summary: String? = null,
    val enabled: Boolean = true,
    val onCheckedChange: (Boolean) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsChoiceRowUiState(
    override val id: String,
    val title: String,
    val value: String,
    val options: List<SettingsChoiceOption<String>>,
    val summary: String? = null,
    val enabled: Boolean = true,
    val onValueChange: (String) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsMultiChoiceRowUiState(
    override val id: String,
    val title: String,
    val values: Set<String>,
    val options: List<SettingsChoiceOption<String>>,
    val emptySelectionText: String,
    val summary: String? = null,
    val enabled: Boolean = true,
    val onValueChange: (Set<String>) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsTextRowUiState(
    override val id: String,
    val title: String,
    val value: String,
    val summary: String? = null,
    val placeholder: String? = null,
    val suggestions: List<SettingsChoiceOption<String>> = emptyList(),
    val isPassword: Boolean = false,
    val enabled: Boolean = true,
    val onValueChange: (String) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsInfoRowUiState(
    override val id: String,
    val title: String,
    val summary: String,
) : SourceSettingsRowUiState

@Composable
fun SourceSettingsScreen(
    sections: List<SourceSettingsSectionUiState>,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        val systemBarsBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        val navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = max(systemBarsBottom, navigationBarsBottom)
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + bottomInset + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sections.forEach { section ->
                item(key = section.id) {
                    SettingsPreferenceSection(title = section.title) {
                        section.rows.forEachIndexed { index, row ->
                            key(row.id) {
                                when (row) {
                                    is SourceSettingsGroupLabelRowUiState -> {
                                        SettingsGroupLabel(
                                            text = row.text,
                                        )
                                    }

                                    is SourceSettingsActionRowUiState -> {
                                        SettingsActionPreference(
                                            title = row.title,
                                            summary = row.summary,
                                            enabled = row.enabled,
                                            showChevron = row.showChevron,
                                            onClick = row.onClick,
                                        )
                                    }

                                    is SourceSettingsSwitchRowUiState -> {
                                        SettingsSwitchPreference(
                                            title = row.title,
                                            checked = row.checked,
                                            summary = row.summary,
                                            enabled = row.enabled,
                                            onCheckedChange = row.onCheckedChange,
                                        )
                                    }

                                    is SourceSettingsChoiceRowUiState -> {
                                        SettingsChoicePreference(
                                            title = row.title,
                                            value = row.value,
                                            options = row.options,
                                            summary = row.summary,
                                            enabled = row.enabled,
                                            onValueChange = row.onValueChange,
                                        )
                                    }

                                    is SourceSettingsMultiChoiceRowUiState -> {
                                        SettingsMultiChoicePreference(
                                            title = row.title,
                                            values = row.values,
                                            options = row.options,
                                            emptySelectionText = row.emptySelectionText,
                                            summary = row.summary,
                                            enabled = row.enabled,
                                            onValueChange = row.onValueChange,
                                        )
                                    }

                                    is SourceSettingsTextRowUiState -> {
                                        SettingsDialogTextPreference(
                                            title = row.title,
                                            value = row.value,
                                            summary = row.summary,
                                            placeholder = row.placeholder,
                                            suggestions = row.suggestions,
                                            isPassword = row.isPassword,
                                            enabled = row.enabled,
                                            onValueChange = row.onValueChange,
                                        )
                                    }

                                    is SourceSettingsInfoRowUiState -> {
                                        SettingsInfoPreference(
                                            title = row.title,
                                            summary = row.summary,
                                        )
                                    }
                                }
                            }
                            if (index < section.rows.lastIndex) {
                                SettingsSectionDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun max(first: Dp, second: Dp): Dp = max(first.value, second.value).dp
