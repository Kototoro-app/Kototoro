package org.skepsun.kototoro.settings.sources.unified

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallPromptDialog
import org.skepsun.kototoro.settings.compose.AndroidPreferenceDialog
import org.skepsun.kototoro.core.extensions.SelectedSourcesDeletePlan
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.rememberTvFieldExit
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.settings.sources.extensions.formatExtensionFingerprint

@Composable
internal fun <T> UnifiedSelectionDialog(
    title: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    SettingsAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { option ->
                    TvTextButton(
                        onClick = { onSelected(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = optionLabel(option),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun UnifiedInstallChoiceDialog(
    kind: UnifiedSourceKind,
    name: String,
    sourceCount: Int,
    onDismiss: () -> Unit,
    onChoice: (ExtensionInstallPolicy, Boolean) -> Unit,
) {
    var rememberChoice by rememberSaveable(kind) { mutableStateOf(false) }
    ExtensionInstallPromptDialog(
        name = name,
        sourceCount = sourceCount,
        kindLabel = stringResource(kind.dialogLabelResId()),
        rememberChoice = rememberChoice,
        onRememberChange = { rememberChoice = it },
        onChoose = { enable ->
            onChoice(if (enable) ExtensionInstallPolicy.INSTALL_AND_ENABLE else ExtensionInstallPolicy.INSTALL_ONLY,
                rememberChoice)
        },
        onDismiss = onDismiss,
        renderDialog = { AndroidPreferenceDialog(it) },
    )
}

@Composable
internal fun UnifiedRepositoryUrlDialog(
    kind: UnifiedSourceKind,
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(kind, initialUrl) { mutableStateOf(initialUrl) }
    val fieldExit = rememberTvFieldExit()
    val hint = when (kind) {
        UnifiedSourceKind.LEGADO -> "https://example.com/legado.json"
        UnifiedSourceKind.TVBOX -> "http://z.qiqiv.cn/123.txt"
        else -> "https://example.com/index.min.json"
    }
    SettingsAlertDialog(
        title = stringResource(R.string.repository_url),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                onClick = { onConfirm(value) },
                modifier = fieldExit.targetModifier,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(fieldExit.fieldModifier),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { fieldExit.onDone() }),
                singleLine = true,
                label = { Text(hint) },
            )
        },
    )
}

@Composable
internal fun UnifiedInlineRepositoryDialog(
    kind: UnifiedSourceKind,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(kind) { mutableStateOf("") }
    val fieldExit = rememberTvFieldExit(multiline = true)
    SettingsAlertDialog(
        title = stringResource(R.string.paste_repository_with_kind, stringResource(kind.dialogLabelResId())),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                onClick = { onConfirm(value) },
                modifier = fieldExit.targetModifier,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .then(fieldExit.fieldModifier),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { fieldExit.onDone() }),
                label = { Text(stringResource(R.string.paste_content)) },
            )
        },
    )
}

@Composable
internal fun UnifiedDisclaimerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsAlertDialog(
        title = stringResource(R.string.add_repository),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.ok),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = { Text(stringResource(R.string.welcome_plugins_disclaimer)) },
    )
}

@Composable
internal fun UnifiedDeleteRepositoryDialog(
    repository: UnifiedSourceRepositoryItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsAlertDialog(
        title = stringResource(R.string.delete_repository_title),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(R.string.delete),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = { Text(stringResource(R.string.delete_repository_message, repository.name)) },
    )
}

@Composable
internal fun UnifiedSetFilteredSourcesEnabledDialog(
    enabled: Boolean,
    sourceCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsAlertDialog(
        title = stringResource(
            if (enabled) {
                R.string.unified_sources_enable_all
            } else {
                R.string.unified_sources_disable_all
            },
        ),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(if (enabled) R.string.enable else R.string.disable),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = {
            Text(
                stringResource(
                    if (enabled) {
                        R.string.unified_sources_enable_all_confirmation
                    } else {
                        R.string.unified_sources_disable_all_confirmation
                    },
                    sourceCount,
                ),
            )
        },
    )
}

@Composable
internal fun UnifiedDeleteSelectedSourcesDialog(
    plan: SelectedSourcesDeletePlan,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val skippedJarText = plan.skippedNames.joinToString(", ")
    val message = when {
        plan.packageIds.isNotEmpty() && plan.skippedNames.isNotEmpty() -> {
            stringResource(
                R.string.unified_sources_delete_selected_with_skipped_jars,
                plan.packageIds.size,
                skippedJarText,
            )
        }
        plan.packageIds.isNotEmpty() -> {
            stringResource(
                R.string.unified_sources_delete_selected_message,
                plan.packageIds.size,
            )
        }
        plan.skippedNames.isNotEmpty() -> {
            stringResource(
                R.string.unified_sources_delete_selected_only_skipped_jars,
                skippedJarText,
            )
        }
        else -> stringResource(R.string.unified_sources_delete_selected_no_packages)
    }
    SettingsAlertDialog(
        title = stringResource(R.string.delete),
        onDismissRequest = onDismiss,
        confirmButton = {
            if (plan.packageIds.isNotEmpty()) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.delete),
                    onClick = onConfirm,
                )
            } else {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = onDismiss,
                )
            }
        },
        dismissButton = {
            if (plan.packageIds.isNotEmpty()) {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                )
            }
        },
        text = { Text(message) },
    )
}

@Composable
internal fun UnifiedTrustRepositoryDialog(
    repo: ExternalExtensionRepo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsAlertDialog(
        title = stringResource(R.string.trust_extension_repository),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(R.string.trust_and_add),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.trust_extension_repository_message,
                    repo.displayName,
                    repo.website,
                    repo.signingKeyFingerprint.formatExtensionFingerprint(),
                ),
            )
        },
    )
}

@Composable
internal fun UnifiedPackageStateDetailsDialog(
    item: UnifiedSourcePackageItem,
    onDismiss: () -> Unit,
    onManageRepositories: () -> Unit,
    onUninstall: () -> Unit,
) {
    val title: String
    val message: String
    when (item.state) {
        UnifiedSourcePackageState.UNTRUSTED -> {
            title = stringResource(R.string.untrusted_extension)
            message = stringResource(
                R.string.untrusted_extension_message,
                item.name,
                item.packageName.orEmpty(),
                item.installPayload?.signatureHash.orEmpty().formatExtensionFingerprint(),
            )
        }
        UnifiedSourcePackageState.INCOMPATIBLE -> {
            title = stringResource(R.string.incompatible_extension)
            message = stringResource(
                R.string.incompatible_extension_message,
                item.name,
                item.versionName.orEmpty(),
                item.installPayload?.libVersion?.toString().orEmpty(),
            )
        }
        else -> return
    }
    SettingsAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(if (item.isInstalled) R.string.remove else android.R.string.ok),
                onClick = if (item.isInstalled) onUninstall else onDismiss,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.manage_extension_repositories),
                    onClick = onManageRepositories,
                )
                if (item.isInstalled) {
                    SettingsDialogActionButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                    )
                }
            }
        },
        text = { Text(message) },
    )
}

@Composable
internal fun UnifiedLanguageFilterDialog(
    languages: List<String>,
    selectedLanguages: Set<String>,
    isStrictLanguageFilter: Boolean = false,
    onStrictLanguageFilterChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onLanguageClick: (String) -> Unit,
    onApplyPreferredLanguages: () -> Unit,
    onClear: () -> Unit,
) {
    LaunchedEffect(languages, selectedLanguages) {
        Log.d(
            "UnifiedLanguageFilter",
            "open language filter languages=$languages selectedLanguages=$selectedLanguages",
        )
    }

    SettingsAlertDialog(
        title = stringResource(R.string.filter_extensions_by_language),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(R.string.done),
                onClick = onDismiss,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.clear),
                    onClick = onClear,
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.use_setup_wizard_languages),
                    onClick = onApplyPreferredLanguages,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onStrictLanguageFilterChange(!isStrictLanguageFilter) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.strict_language_filter),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.strict_language_filter_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isStrictLanguageFilter,
                        onCheckedChange = onStrictLanguageFilterChange,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(languages, key = { it }) { language ->
                        CompactFilterChip(
                            selected = language in selectedLanguages,
                            onClick = { onLanguageClick(language) },
                            text = language.displayLanguageLabel(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun UnifiedCombinedFilterDialog(
    readyState: UnifiedSourcesUiState.Ready,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    onLanguageClick: (String) -> Unit,
    onApplyPreferredLanguages: () -> Unit,
    onStrictLanguageFilterChange: (Boolean) -> Unit,
    onEnabledFilterChange: (UnifiedEnabledFilter) -> Unit,
    onAvailabilityFilterChange: (UnifiedAvailabilityFilter) -> Unit,
    onTestAvailabilityFilterChange: (UnifiedTestAvailabilityFilter) -> Unit,
    onNsfwFilterChange: (UnifiedNsfwFilter) -> Unit,
    onLockFilterChange: (UnifiedLockFilter) -> Unit,
    onLocationTypeToggle: (UnifiedRepositoryLocationType) -> Unit,
    initialTab: Int = 0,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val generalCount = readyState.filters.otherFilterCount()
    val languageCount = readyState.filters.languages.size

    SettingsAlertDialog(
        title = stringResource(R.string.filter),
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogActionButton(
                text = stringResource(R.string.done),
                onClick = onDismiss,
            )
        },
        dismissButton = {
            SettingsDialogActionButton(
                text = stringResource(R.string.clear),
                onClick = onClearAll,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val activeSurface = MaterialTheme.colorScheme.surface
                            val baseBorderColor = MaterialTheme.colorScheme.outlineVariant
                            val tabShape = RoundedCornerShape(16.dp)

                            val tab0Selected = selectedTab == 0
                            val tab0BgColor by animateColorAsState(
                                targetValue = if (tab0Selected) activeSurface else activeSurface.copy(alpha = 0f),
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab0Bg",
                            )
                            val tab0TextColor by animateColorAsState(
                                targetValue = if (tab0Selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab0Text",
                            )
                            val tab0BorderColor by animateColorAsState(
                                targetValue = if (tab0Selected) baseBorderColor.copy(alpha = 0.35f) else baseBorderColor.copy(alpha = 0f),
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab0Border",
                            )

                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(tabShape)
                                    .background(tab0BgColor)
                                    .border(BorderStroke(0.5.dp, tab0BorderColor), tabShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = 0 },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.filter_properties),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (tab0Selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = tab0TextColor,
                                    )
                                    if (generalCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(percent = 50),
                                            color = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ) {
                                            Text(
                                                text = generalCount.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.5.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            val tab1Selected = selectedTab == 1
                            val tab1BgColor by animateColorAsState(
                                targetValue = if (tab1Selected) activeSurface else activeSurface.copy(alpha = 0f),
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab1Bg",
                            )
                            val tab1TextColor by animateColorAsState(
                                targetValue = if (tab1Selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab1Text",
                            )
                            val tab1BorderColor by animateColorAsState(
                                targetValue = if (tab1Selected) baseBorderColor.copy(alpha = 0.35f) else baseBorderColor.copy(alpha = 0f),
                                animationSpec = tween(durationMillis = 200),
                                label = "dialogTab1Border",
                            )

                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(tabShape)
                                    .background(tab1BgColor)
                                    .border(BorderStroke(0.5.dp, tab1BorderColor), tabShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = 1 },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.filter_language),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (tab1Selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = tab1TextColor,
                                    )
                                    if (languageCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(percent = 50),
                                            color = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ) {
                                            Text(
                                                text = languageCount.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.5.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedTab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterSection(title = stringResource(R.string.status)) {
                            items(UnifiedEnabledFilter.entries) { filter ->
                                CompactFilterChip(
                                    selected = readyState.filters.enabledFilter == filter,
                                    onClick = { onEnabledFilterChange(filter) },
                                    text = filter.displayLabel(),
                                )
                            }
                        }
                        FilterSection(title = stringResource(R.string.availability_filter_title)) {
                            items(UnifiedAvailabilityFilter.entries) { filter ->
                                CompactFilterChip(
                                    selected = readyState.filters.availabilityFilter == filter,
                                    onClick = { onAvailabilityFilterChange(filter) },
                                    text = filter.displayLabel(),
                                )
                            }
                        }
                        FilterSection(title = stringResource(R.string.source_test_availability_filter_title)) {
                            items(UnifiedTestAvailabilityFilter.entries) { filter ->
                                CompactFilterChip(
                                    selected = readyState.filters.testAvailabilityFilter == filter,
                                    onClick = { onTestAvailabilityFilterChange(filter) },
                                    text = filter.displayLabel(),
                                )
                            }
                        }
                        FilterSection(title = stringResource(R.string.nsfw_filter_title)) {
                            items(UnifiedNsfwFilter.entries) { filter ->
                                CompactFilterChip(
                                    selected = readyState.filters.nsfwFilter == filter,
                                    onClick = { onNsfwFilterChange(filter) },
                                    text = filter.displayLabel(),
                                )
                            }
                        }
                        FilterSection(title = stringResource(R.string.source_lock_filter_title)) {
                            items(UnifiedLockFilter.entries) { filter ->
                                CompactFilterChip(
                                    selected = readyState.filters.lockFilter == filter,
                                    onClick = { onLockFilterChange(filter) },
                                    text = filter.displayLabel(),
                                )
                            }
                        }
                        if (readyState.availableLocationTypes.isNotEmpty()) {
                            FilterSection(title = stringResource(R.string.repository_source)) {
                                items(readyState.availableLocationTypes) { type ->
                                    CompactFilterChip(
                                        selected = type in readyState.filters.locationTypes,
                                        onClick = { onLocationTypeToggle(type) },
                                        text = type.displayLabel(),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onStrictLanguageFilterChange(!readyState.filters.isStrictLanguageFilter) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.strict_language_filter),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.strict_language_filter_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = readyState.filters.isStrictLanguageFilter,
                                onCheckedChange = onStrictLanguageFilterChange,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TvTextButton(
                                onClick = onApplyPreferredLanguages,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_language),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.use_setup_wizard_languages),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            gridItems(readyState.availableLanguages, key = { it }) { language ->
                                CompactFilterChip(
                                    selected = language in readyState.filters.languages,
                                    onClick = { onLanguageClick(language) },
                                    text = language.displayLanguageLabel(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

