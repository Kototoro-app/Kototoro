package org.skepsun.kototoro.settings.compose

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceSection
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.sourcePreferenceRowTitle
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.core.ui.settings.PreferenceChoiceDialog
import org.skepsun.kototoro.core.ui.settings.PreferenceMultiChoiceDialog
import org.skepsun.kototoro.core.ui.settings.PreferenceTextDialog
import org.skepsun.kototoro.core.ui.settings.SourcePreferencesContent

sealed interface SourceSettingsRowUiState {
    val id: String
}

data class SourceSettingsSectionUiState(
    val id: String,
    val title: String,
    val rows: List<SourceSettingsRowUiState>,
    /** Set instead of [rows] when the section is a source's own preference tree, drawn by the shared content. */
    val preferences: SourcePreferencesSectionUiState? = null,
)

/**
 * A source's own preferences as the protocol tree Android now builds from its androidx screen: the shared
 * `SourcePreferencesContent` draws the sections, while [onWrite] keeps the extension's `onPreferenceChange` veto and
 * [onAction] its click listener or `Intent`.
 */
class SourcePreferencesSectionUiState(
    val sections: List<SourcePreferenceSection>,
    val active: Boolean,
    val unsupportedText: String,
    val onWrite: (SourcePreferenceNode, SourcePreferenceValue) -> Boolean,
    val onAction: (SourcePreferenceNode) -> Unit,
)

data class SourceSettingsActionRowUiState(
    override val id: String,
    val title: String,
    val summary: String? = null,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val showChevron: Boolean = true,
    val onClick: () -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsSwitchRowUiState(
    override val id: String,
    val title: String,
    val checked: Boolean,
    val summary: String? = null,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val onLockClick: (() -> Unit)? = null,
    val onCheckedChange: (Boolean) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsChoiceRowUiState(
    override val id: String,
    val title: String,
    val value: String,
    val options: List<SettingsChoiceOption<String>>,
    val summary: String? = null,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val onValueChange: (String) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsTextRowUiState(
    override val id: String,
    val title: String,
    val value: String,
    val summary: String? = null,
    val placeholder: String? = null,
    val suggestions: List<SettingsChoiceOption<String>> = emptyList(),
    val isPassword: Boolean = false,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val onValueChange: (String) -> Unit,
) : SourceSettingsRowUiState

data class SourceSettingsInfoRowUiState(
    override val id: String,
    val title: String,
    val summary: String,
    @DrawableRes val iconRes: Int? = null,
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
        // The editor outlives the row that opened it: a lazy item is disposed as soon as it scrolls out of view.
        var preferencesDialog by remember { mutableStateOf<SourcePreferencesDialog?>(null) }
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        val systemBarsBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        val navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = max(systemBarsBottom, navigationBarsBottom)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(),
                bottom = innerPadding.calculateBottomPadding() + bottomInset + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sections.forEach { section ->
                item(key = section.id) {
                    val preferences = section.preferences
                    if (preferences != null) {
                        SourcePreferencesSection(preferences) { dialog ->
                            preferencesDialog = SourcePreferencesDialog(preferences, dialog)
                        }
                        return@item
                    }
                    SettingsPreferenceGroup(title = section.title) {
                        section.rows.forEach { row ->
                            item {
                                key(row.id) {
                                    when (row) {
                                        is SourceSettingsActionRowUiState -> {
                                            SettingsActionPreference(
                                                title = row.title,
                                                summary = row.summary,
                                                iconRes = row.iconRes ?: R.drawable.ic_settings,
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
                                                iconRes = row.iconRes ?: R.drawable.ic_toggle,
                                                enabled = row.enabled,
                                                onCheckedChange = row.onCheckedChange,
                                                locked = row.locked,
                                                onLockClick = row.onLockClick,
                                            )
                                        }

                                        is SourceSettingsChoiceRowUiState -> {
                                            SettingsChoicePreference(
                                                title = row.title,
                                                value = row.value,
                                                options = row.options,
                                                summary = row.summary,
                                                iconRes = row.iconRes ?: R.drawable.ic_select,
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
                                                iconRes = row.iconRes ?: R.drawable.ic_edit,
                                                enabled = row.enabled,
                                                onValueChange = row.onValueChange,
                                            )
                                        }

                                        is SourceSettingsInfoRowUiState -> {
                                            SettingsInfoPreference(
                                                title = row.title,
                                                summary = row.summary,
                                                iconRes = row.iconRes ?: R.drawable.ic_info_outline,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        preferencesDialog?.let { open ->
            SourcePreferenceDialogHost(open.host, open.dialog) { preferencesDialog = null }
        }
    }
}

private fun max(first: Dp, second: Dp): Dp = max(first.value, second.value).dp

@Composable
private fun SourcePreferencesSection(
    host: SourcePreferencesSectionUiState,
    onOpenDialog: (SourcePreferenceDialog) -> Unit,
) {
    SourcePreferencesContent(
        sections = host.sections,
        active = host.active,
        unsupportedText = host.unsupportedText,
        onToggle = { node, checked -> host.onWrite(node, SourcePreferenceValue.Toggle(checked)) },
        onOpenChoice = { node -> onOpenDialog(SourcePreferenceDialog.Choice(node)) },
        onOpenMultiChoice = { node -> onOpenDialog(SourcePreferenceDialog.MultiChoice(node)) },
        onOpenText = { node -> onOpenDialog(SourcePreferenceDialog.Text(node)) },
        onAction = host.onAction,
    )
}

private data class SourcePreferencesDialog(
    val host: SourcePreferencesSectionUiState,
    val dialog: SourcePreferenceDialog,
)

private sealed interface SourcePreferenceDialog {
    val node: SourcePreferenceNode

    data class Choice(override val node: SourcePreferenceNode) : SourcePreferenceDialog
    data class MultiChoice(override val node: SourcePreferenceNode) : SourcePreferenceDialog
    data class Text(override val node: SourcePreferenceNode) : SourcePreferenceDialog
}

/** The three editors a protocol row opens; a rejected write keeps the editor open, as it does on the host screens. */
@Composable
private fun SourcePreferenceDialogHost(
    host: SourcePreferencesSectionUiState,
    dialog: SourcePreferenceDialog,
    onDismiss: () -> Unit,
) {
    val node = dialog.node
    val title = sourcePreferenceRowTitle(node)
    val summary = node.summary.takeIf(String::isNotBlank)
    val options = node.choices.map { PreferenceChoice(value = it.value, label = it.title) }
    val tagPrefix = "preference:${node.id}"
    when (dialog) {
        is SourcePreferenceDialog.Choice -> PreferenceChoiceDialog(
            title = title,
            selected = (node.value as? SourcePreferenceValue.Text)?.value,
            options = options,
            onSelect = { host.onWrite(node, SourcePreferenceValue.Text(it)) },
            onDismiss = onDismiss,
            enabled = node.enabled,
            tagPrefix = tagPrefix,
            renderDialog = { AndroidPreferenceDialog(it) },
        )

        is SourcePreferenceDialog.MultiChoice -> PreferenceMultiChoiceDialog(
            title = title,
            selected = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty(),
            options = options,
            onValueChange = { host.onWrite(node, SourcePreferenceValue.TextSet(it)) },
            onDismiss = onDismiss,
            enabled = node.enabled,
            summary = summary,
            tagPrefix = tagPrefix,
            renderDialog = { AndroidPreferenceDialog(it) },
        )

        is SourcePreferenceDialog.Text -> PreferenceTextDialog(
            title = title,
            initial = (node.value as? SourcePreferenceValue.Text)?.value.orEmpty(),
            onSave = { host.onWrite(node, SourcePreferenceValue.Text(it)) },
            onDismiss = onDismiss,
            enabled = node.enabled,
            secret = node.isSecret,
            tagPrefix = tagPrefix,
            renderDialog = { AndroidPreferenceDialog(it) },
        )
    }
}
