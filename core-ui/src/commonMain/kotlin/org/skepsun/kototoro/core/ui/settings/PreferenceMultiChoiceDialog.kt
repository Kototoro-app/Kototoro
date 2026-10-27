package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.theme.LocalIsIosInterfaceStyle
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.settings.compose.SettingsGroupDivider
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons

/** Confirmed editors own a private draft; live editors always display the host's acknowledged selection. */
@Composable
fun <T> PreferenceMultiChoiceDialog(
    title: String,
    selected: Set<T>,
    options: List<PreferenceChoice<T>>,
    onValueChange: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    maxSelections: Int? = null,
    commitOnToggle: Boolean = false,
    summary: String? = null,
    tagPrefix: String = "preference-multi-choice",
    dialogTag: String = "$tagPrefix-dialog",
    optionTagPrefix: String = "$tagPrefix-option",
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var draft by remember(selected) { mutableStateOf(selected) }
    val values = if (commitOnToggle) selected else draft
    val isIosStyle = LocalIsIosInterfaceStyle.current
    renderDialog(PreferenceDialogContent(
        title = title,
        onDismiss = onDismiss,
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag(dialogTag)) {
                if (summary != null) item {
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                itemsIndexed(options) { index, option ->
                    val checked = option.value in values
                    val optionEnabled = enabled && (checked || maxSelections == null || values.size < maxSelections)
                    Row(Modifier.fillMaxWidth().heightIn(min = if (isIosStyle) 48.dp else 0.dp)
                        .tvFocusable(enabled = optionEnabled, shape = RoundedCornerShape(10.dp), addFocusTarget = false)
                        .toggleable(checked, enabled = optionEnabled, role = Role.Checkbox) { check ->
                            val next = if (check) values + option.value else values - option.value
                            if (commitOnToggle) onValueChange(next) else draft = next
                        }.padding(vertical = 6.dp).testTag("$optionTagPrefix:${option.value}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!isIosStyle) {
                            Checkbox(checked, onCheckedChange = null, enabled = optionEnabled)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (optionEnabled) 1f else 0.38f))
                        if (isIosStyle && checked) {
                            Icon(SettingsMaterialIcons.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (isIosStyle && index != options.lastIndex) {
                        SettingsGroupDivider(startPadding = 0.dp, endPadding = 0.dp)
                    }
                }
            }
        },
        confirmButton = {
            if (commitOnToggle) {
                SettingsDialogActionButton(uiString("close"), onClick = onDismiss,
                    modifier = Modifier.testTag("$dialogTag-close"))
            } else {
                SettingsDialogActionButton(uiString("ok"), enabled = enabled,
                    onClick = { onValueChange(draft); onDismiss() }, modifier = Modifier.testTag("$tagPrefix-save"))
            }
        },
        dismissButton = if (commitOnToggle) null else ({
            SettingsDialogActionButton(uiString("cancel"), onClick = onDismiss,
                modifier = Modifier.testTag("$tagPrefix-cancel"))
        }),
    ))
}
