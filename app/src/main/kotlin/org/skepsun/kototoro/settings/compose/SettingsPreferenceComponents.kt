package org.skepsun.kototoro.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import kotlin.math.roundToInt

data class SettingsChoiceOption<T>(
    val value: T,
    val label: String,
)

@Composable
private fun settingsSwitchColors(): SwitchColors {
    return if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )
    } else {
        SwitchDefaults.colors()
    }
}

@Composable
fun SettingsGroupSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.groupCornerRadius),
        color = if (isIosStyle) {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.74f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
        },
        border = if (isIosStyle) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f))
        } else {
            null
        },
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsPreferenceSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (expressive) 8.dp else 6.dp),
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = if (expressive) 10.dp else 8.dp),
            )
        }
        if (expressive || isIosStyle) {
            SettingsGroupSurface {
                content()
            }
        } else {
            content()
        }
    }
}

@Composable
fun SettingsGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            horizontal = if (expressive) 16.dp else 20.dp,
            vertical = 10.dp,
        ),
    )
}

@Composable
fun SettingsActionPreference(
    title: String,
    summary: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsSplitSwitchPreference(
    title: String,
    checked: Boolean,
    summary: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = rememberSafePainter(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = settingsSwitchColors(),
        )
    }
}

@Composable
fun SettingsInfoPreference(
    title: String,
    summary: String,
    @DrawableRes iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .settingsPreferenceLayout(enabled = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsSwitchPreference(
    title: String,
    checked: Boolean,
    summary: String? = null,
    styleHint: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (styleHint != null) {
                Text(
                    text = styleHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = settingsSwitchColors(),
        )
    }
}

@Composable
fun <T> SettingsChoicePreference(
    title: String,
    value: T,
    options: List<SettingsChoiceOption<T>>,
    summary: String? = null,
    styleHint: String? = null,
    enabled: Boolean = true,
	onSettingsClick: (() -> Unit)? = null,
	settingsContentDescription: String? = null,
    onValueChange: (T) -> Unit,
) {
    var isDialogVisible by remember { mutableStateOf(false) }
    var currentValue by remember(value) { mutableStateOf(value) }
    val selectedLabel = options.firstOrNull { it.value == currentValue }?.label.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { isDialogVisible = true }
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (styleHint != null) {
                Text(
                    text = styleHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
		if (onSettingsClick != null) {
			IconButton(onClick = onSettingsClick, enabled = enabled) {
				Icon(
					imageVector = Icons.Filled.Settings,
					contentDescription = settingsContentDescription,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isDialogVisible) {
        val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
        SettingsAlertDialog(
            title = title,
            onDismissRequest = { isDialogVisible = false },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    itemsIndexed(options, contentType = { _, _ -> "radio_option" }) { index, option ->
                        val selected = option.value == currentValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (isIosStyle) 48.dp else 0.dp)
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        currentValue = option.value
                                        onValueChange(option.value)
                                        isDialogVisible = false
                                    },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!isIosStyle) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = option.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (isIosStyle && selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (isIosStyle && index != options.lastIndex) {
                            SettingsGroupDivider(startPadding = 0.dp, endPadding = 0.dp)
                        }
                    }
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = { isDialogVisible = false },
                )
            },
        )
    }
}

@Composable
fun <T> SettingsMultiChoicePreference(
    title: String,
    values: Set<T>,
    options: List<SettingsChoiceOption<T>>,
    emptySelectionText: String,
    summary: String? = null,
    maxSelections: Int? = null,
    enabled: Boolean = true,
    onValueChange: (Set<T>) -> Unit,
) {
    var isDialogVisible by remember { mutableStateOf(false) }
    var pendingValues by remember(values) { mutableStateOf(values) }
    val selectedLabel = options
        .filter { it.value in values }
        .joinToString { it.label }
        .ifBlank { emptySelectionText }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                pendingValues = values
                isDialogVisible = true
            }
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isDialogVisible) {
        val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
        SettingsAlertDialog(
            title = title,
            onDismissRequest = { isDialogVisible = false },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    itemsIndexed(options, contentType = { _, _ -> "checkbox_option" }) { index, option ->
                        val checked = option.value in pendingValues
                        val optionEnabled = checked || maxSelections == null || pendingValues.size < maxSelections
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (isIosStyle) 48.dp else 0.dp)
                                .toggleable(
                                    value = checked,
                                    enabled = optionEnabled,
                                    onValueChange = { checked ->
                                        pendingValues = if (checked) {
                                            pendingValues + option.value
                                        } else {
                                            pendingValues - option.value
                                        }
                                    },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!isIosStyle) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    enabled = optionEnabled,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = option.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (optionEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                            if (isIosStyle && checked) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (isIosStyle && index != options.lastIndex) {
                            SettingsGroupDivider(startPadding = 0.dp, endPadding = 0.dp)
                        }
                    }
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        onValueChange(pendingValues)
                        isDialogVisible = false
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isDialogVisible = false },
                )
            },
        )
    }
}

@Composable
fun SettingsSliderPreference(
    title: String,
    value: Int,
    valueRange: IntRange,
    step: Int,
    summary: String? = null,
    enabled: Boolean = true,
    valueText: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }
    var committedValue by remember(value) { mutableStateOf(value.coerceIn(valueRange.first, valueRange.last)) }
    val steps = ((valueRange.last - valueRange.first) / step - 1).coerceAtLeast(0)
    val currentValue = sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)

    Column(
        modifier = Modifier
            .settingsPreferenceLayout(enabled),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = valueText(currentValue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KototoroSlider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                val nextValue = it.roundToInt().coerceIn(valueRange.first, valueRange.last)
                if (nextValue != committedValue) {
                    committedValue = nextValue
                    onValueChange(nextValue)
                }
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
            enabled = enabled,
            compactThumb = true,
            onValueChangeFinished = {
                if (currentValue != committedValue) {
                    committedValue = currentValue
                    onValueChange(currentValue)
                }
            },
        )
    }
}

@Composable
fun SettingsTextInputPreference(
    title: String,
    value: String,
    summary: String? = null,
    placeholder: String? = null,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var currentValue by remember(value) { mutableStateOf(value) }

    Column(
        modifier = Modifier
            .settingsPreferenceLayout(enabled),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = currentValue,
            onValueChange = {
                currentValue = it
                onValueChange(it)
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let {
                { Text(text = it) }
            },
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
    }
}

@Composable
fun SettingsDialogTextPreference(
    title: String,
    value: String,
    summary: String? = null,
    placeholder: String? = null,
    suggestions: List<SettingsChoiceOption<String>> = emptyList(),
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var isDialogVisible by remember { mutableStateOf(false) }
    var pendingValue by remember(value) { mutableStateOf(value) }
    var isSuggestionsExpanded by remember { mutableStateOf(false) }
    val displayValue = when {
        isPassword && value.isNotEmpty() -> "\u2022".repeat(value.length.coerceAtMost(8))
        value.isNotBlank() -> value
        !placeholder.isNullOrBlank() -> placeholder
        else -> stringResource(R.string.not_specified)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                pendingValue = value
                isDialogVisible = true
            }
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isDialogVisible) {
        val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
        SettingsAlertDialog(
            title = title,
            onDismissRequest = {
                isSuggestionsExpanded = false
                isDialogVisible = false
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = pendingValue,
                        onValueChange = {
                            pendingValue = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = placeholder?.let { { Text(text = it) } },
                        trailingIcon = if (suggestions.isNotEmpty()) {
                            {
                                IconButton(onClick = { isSuggestionsExpanded = !isSuggestionsExpanded }) {
                                    Icon(
                                        imageVector = if (isSuggestionsExpanded) {
                                            Icons.Filled.KeyboardArrowUp
                                        } else {
                                            Icons.Filled.KeyboardArrowDown
                                        },
                                        contentDescription = null,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        visualTransformation = if (isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    )
                    DropdownMenu(
                        expanded = isSuggestionsExpanded && suggestions.isNotEmpty(),
                        onDismissRequest = { isSuggestionsExpanded = false },
                        shape = if (isIosStyle) RoundedCornerShape(14.dp) else MaterialTheme.shapes.large,
                    ) {
                        suggestions.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(text = suggestion.label) },
                                onClick = {
                                    pendingValue = suggestion.value
                                    isSuggestionsExpanded = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        onValueChange(pendingValue)
                        isSuggestionsExpanded = false
                        isDialogVisible = false
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = {
                        isSuggestionsExpanded = false
                        isDialogVisible = false
                    },
                )
            },
        )
    }
}

@Composable
fun SettingsReorderPreference(
    title: String,
    value: List<String>,
    summary: String? = null,
    emptyValueText: String,
    enabled: Boolean = true,
    onValueChange: (List<String>) -> Unit,
) {
    var isDialogVisible by remember { mutableStateOf(false) }
    var pendingValue by remember(value) { mutableStateOf(value) }
    val displayValue = value.joinToString(", ").ifBlank { emptyValueText }
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                pendingValue = value
                isDialogVisible = true
            }
            .settingsPreferenceLayout(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isDialogVisible) {
        SettingsAlertDialog(
            title = title,
            onDismissRequest = { isDialogVisible = false },
            text = {
                if (pendingValue.isEmpty()) {
                    Text(
                        text = emptyValueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        itemsIndexed(pendingValue, key = { _, item -> item }) { index: Int, item: String ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.removeSuffix(".jar"),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            pendingValue = pendingValue.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = moveUpLabel,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < pendingValue.lastIndex) {
                                            pendingValue = pendingValue.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index < pendingValue.lastIndex,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = moveDownLabel,
                                    )
                                }
                            }
                            if (index != pendingValue.lastIndex) {
                                SettingsSectionDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        onValueChange(pendingValue)
                        isDialogVisible = false
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isDialogVisible = false },
                )
            },
        )
    }
}

@Composable
fun SettingsSectionDivider() {
    SettingsGroupDivider()
}

@Composable
fun SettingsGroupDivider(
    startPadding: Dp = 20.dp,
    endPadding: Dp = 20.dp,
) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startPadding, end = endPadding),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
    )
}

@Composable
private fun Modifier.settingsPreferenceLayout(enabled: Boolean): Modifier {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val tokens = LocalInterfaceStyleTokens.current
    return fillMaxWidth()
        .alpha(if (enabled) 1f else 0.5f)
        .then(
            if (expressive || isIosStyle) {
                Modifier
                    .heightIn(min = if (isIosStyle) 62.dp else tokens.controlHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            } else {
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            },
        )
}
