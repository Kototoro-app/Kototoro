package org.skepsun.kototoro.settings.compose.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassCustomPreset
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassScopeConfig
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassTuning
import org.skepsun.kototoro.core.ui.glass.LocalGlassTuning
import org.skepsun.kototoro.core.ui.glass.GlassTuningParam
import org.skepsun.kototoro.core.ui.glass.GlassTuningScope
import org.skepsun.kototoro.core.ui.glass.GlassTuningState
import org.skepsun.kototoro.core.ui.glass.ParamKind
import org.skepsun.kototoro.core.ui.theme.LocalAmoledTheme
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import java.text.DecimalFormat

/**
 * The "Glass 质感微调" section of the rebuilt Appearance page (ADR 0001).
 * Presents the live preview, the quality presets, the Glass master switches and
 * the six scopes (Global + five roles) as compact, orthogonal controls.
 */
@Composable
fun GlassTuningSection(
    tuning: GlassTuningState,
    isGlassEffectEnabled: Boolean,
    isReducedVisualEffects: Boolean,
    immersiveStrengthPercent: Int,
    onGlassEffectEnabledChange: (Boolean) -> Unit,
    onReducedVisualEffectsChange: (Boolean) -> Unit,
    onImmersiveStrengthChange: (Int) -> Unit,
    onSetValue: (GlassTuningScope, GlassTuningParam, Float) -> Unit,
    onSetFollowGlobal: (GlassTuningScope, GlassTuningParam, Boolean) -> Unit,
    onApplyPreset: (GlassPreset) -> Unit,
    onRestoreDefaults: () -> Unit,
    customPresets: List<GlassCustomPreset> = emptyList(),
    onSaveCustomPreset: () -> Unit = {},
    onApplyCustomPreset: (GlassCustomPreset) -> Unit = {},
    onDeleteCustomPreset: (GlassCustomPreset) -> Unit = {},
    onExportCustomPresets: () -> Unit = {},
    onImportCustomPresets: () -> Unit = {},
) {
    var previewValues by remember {
        mutableStateOf<Map<Pair<GlassTuningScope, GlassTuningParam>, Float>>(emptyMap())
    }
    val previewTuning = remember(tuning, previewValues) { tuning.withValues(previewValues) }
    CompositionLocalProvider(LocalGlassTuning provides previewTuning) {
        GlassTuningPreview()
    }
    Spacer(modifier = Modifier.height(4.dp))
    // Glass master switches (global rendering pipeline controls, kept outside
    // the per-parameter tuning model).
    GlassMasterSwitch(
        title = stringResource(R.string.pref_glass_effect),
        summary = stringResource(R.string.pref_glass_effect_summary),
        checked = isGlassEffectEnabled,
        onCheckedChange = onGlassEffectEnabledChange,
    )
    GlassMasterSwitch(
        title = stringResource(R.string.pref_reduce_visual_effects),
        summary = stringResource(R.string.pref_reduce_visual_effects_summary),
        checked = isReducedVisualEffects,
        onCheckedChange = onReducedVisualEffectsChange,
    )
    GlassImmersiveSlider(
        value = immersiveStrengthPercent,
        onValueChange = onImmersiveStrengthChange,
    )
    HorizontalDivider()
    GlassPresetRow(
        tuning = tuning,
        customPresets = customPresets,
        onApplyPreset = {
            previewValues = emptyMap()
            onApplyPreset(it)
        },
        onApplyCustomPreset = {
            previewValues = emptyMap()
            onApplyCustomPreset(it)
        },
        onSaveCustomPreset = onSaveCustomPreset,
        onDeleteCustomPreset = onDeleteCustomPreset,
        onExportCustomPresets = onExportCustomPresets,
        onImportCustomPresets = onImportCustomPresets,
        onRestoreDefaults = {
            previewValues = emptyMap()
            onRestoreDefaults()
        },
    )
    HorizontalDivider()
    // Global baseline scope.
    GlassScopeHeader(
        title = stringResource(R.string.pref_glass_scope_global),
        subtitle = stringResource(R.string.pref_glass_scope_global_desc),
        expanded = true,
        onToggle = {},
        enabled = false,
    )
    GlassTuning.paramsForScope(GlassTuningScope.GLOBAL)
        .forEach { param ->
            GlassParamRow(
                param = param,
                value = previewTuning.value(GlassTuningScope.GLOBAL, param),
                enabled = true,
                onValuePreview = { value ->
                    previewValues = previewValues + ((GlassTuningScope.GLOBAL to param) to value)
                },
                onValueChange = { value ->
                    onSetValue(GlassTuningScope.GLOBAL, param, value)
                    previewValues = previewValues - (GlassTuningScope.GLOBAL to param)
                },
            )
        }
    // Per-role override scopes (collapsible; seed with the two most impactful).
    GlassTuningScope.entries
        .filter { it != GlassTuningScope.GLOBAL }
        .forEach { scope ->
            GlassRoleScope(
                scope = scope,
                tuning = previewTuning,
                onPreviewValue = { param, value ->
                    previewValues = previewValues + ((scope to param) to value)
                },
                onSetValue = { targetScope, param, value ->
                    onSetValue(targetScope, param, value)
                    previewValues = previewValues - (targetScope to param)
                },
                onSetFollowGlobal = onSetFollowGlobal,
            )
        }
}

/** One-line master switch with an icon. */
@Composable
private fun GlassMasterSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun GlassImmersiveSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var pendingValue by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pref_glass_immersive_strength),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.pref_glass_immersive_strength_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$pendingValue%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = pendingValue.toFloat(),
            onValueChange = { pendingValue = it.toInt() },
            onValueChangeFinished = { onValueChange(pendingValue) },
            valueRange = 0f..100f,
            steps = 9,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun GlassPresetRow(
    tuning: GlassTuningState,
    customPresets: List<GlassCustomPreset>,
    onApplyPreset: (GlassPreset) -> Unit,
    onApplyCustomPreset: (GlassCustomPreset) -> Unit,
    onSaveCustomPreset: () -> Unit,
    onDeleteCustomPreset: (GlassCustomPreset) -> Unit,
    onExportCustomPresets: () -> Unit,
    onImportCustomPresets: () -> Unit,
    onRestoreDefaults: () -> Unit,
) {
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GlassCustomPreset?>(null) }
    val activePreset = GlassPreset.entries.firstOrNull { it.matches(tuning) }
    val activeCustom = if (activePreset == null) {
        customPresets.firstOrNull { it.matches(tuning) }
    } else {
        null
    }
    if (showRestoreConfirmation) {
        SettingsAlertDialog(
            title = stringResource(R.string.pref_glass_restore_defaults),
            onDismissRequest = { showRestoreConfirmation = false },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.pref_glass_restore_defaults),
                    onClick = {
                        showRestoreConfirmation = false
                        onRestoreDefaults()
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showRestoreConfirmation = false },
                )
            },
        ) {
            Text(stringResource(R.string.pref_glass_restore_defaults_message))
        }
    }
    deleteTarget?.let { target ->
        SettingsAlertDialog(
            title = stringResource(R.string.pref_glass_delete_preset),
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.pref_glass_delete_preset),
                    onClick = {
                        deleteTarget = null
                        onDeleteCustomPreset(target)
                    },
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { deleteTarget = null },
                )
            },
        ) {
            Text(stringResource(R.string.pref_glass_delete_preset_message, target.name))
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pref_glass_preset),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetRowAction(
                text = stringResource(R.string.pref_glass_save_preset),
                onClick = onSaveCustomPreset,
            )
            PresetRowAction(
                text = stringResource(R.string.pref_glass_export_presets),
                onClick = onExportCustomPresets,
            )
            PresetRowAction(
                text = stringResource(R.string.pref_glass_import_presets),
                onClick = onImportCustomPresets,
            )
            PresetRowAction(
                text = stringResource(R.string.pref_glass_restore_defaults),
                onClick = { showRestoreConfirmation = true },
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GlassPreset.entries.forEach { preset ->
                FilterChip(
                    selected = activePreset == preset,
                    onClick = { onApplyPreset(preset) },
                    label = { Text(stringResource(preset.titleRes)) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
            customPresets.forEach { preset ->
                FilterChip(
                    selected = activeCustom == preset,
                    onClick = { onApplyCustomPreset(preset) },
                    label = { Text(preset.name, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.pref_glass_delete_preset),
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .clickable(onClick = { deleteTarget = preset })
                                .padding(2.dp),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
            FilterChip(
                selected = activePreset == null && activeCustom == null,
                onClick = {},
                label = { Text(stringResource(R.string.pref_glass_preset_custom)) },
            )
        }
        // Expose the active preset's per-role logic: which roles deviate from
        // the global baseline and by how much (e.g. Control Center drops menu
        // shadows and softens pill refraction).
        val activeRoleOverrides = when {
            activePreset != null && activePreset.roleOverrides.isNotEmpty() ->
                activePreset.roleOverrides
            activeCustom != null && activeCustom.scopeOverrides().isNotEmpty() ->
                activeCustom.scopeOverrides()
            else -> null
        }
        if (activeRoleOverrides != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.pref_glass_role_delta),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = presetRoleDeltaSummary(activeRoleOverrides),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun presetRoleDeltaSummary(roleOverrides: Map<GlassTuningScope, Map<String, Float>>): String {
    val parts = mutableListOf<String>()
    for ((scope, overrides) in roleOverrides) {
        val scopeName = stringResource(roleTitles[scope] ?: R.string.pref_glass_scope_global)
        val items = mutableListOf<String>()
        for ((key, value) in overrides) {
            val param = GlassTuningParam.fromKey(key)
            items += if (param == null) {
                "$key $value"
            } else {
                stringResource(paramTitleRes(param)) + " " + formatParamValue(param, value)
            }
        }
        parts += "$scopeName：${items.joinToString("、")}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun PresetRowAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Section header used for the Global (always expanded) scope. */
@Composable
private fun GlassScopeHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    val stateLabel = stringResource(if (expanded) R.string.collapse else R.string.expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
        )
    }
}

private val roleTitles: Map<GlassTuningScope, Int> = mapOf(
    GlassTuningScope.TOP_BAR to R.string.pref_glass_scope_top_bar,
    GlassTuningScope.BOTTOM_BAR to R.string.pref_glass_scope_bottom_bar,
    GlassTuningScope.PILL_CONTROL to R.string.pref_glass_scope_pill,
    GlassTuningScope.BOTTOM_PANEL to R.string.pref_glass_scope_bottom_panel,
    GlassTuningScope.MENU to R.string.pref_glass_scope_menu,
)

/** What each tunable scope maps to in the app, shown in the scope headers. */
private val roleDescriptions: Map<GlassTuningScope, Int> = mapOf(
    GlassTuningScope.TOP_BAR to R.string.pref_glass_scope_top_bar_desc,
    GlassTuningScope.BOTTOM_BAR to R.string.pref_glass_scope_bottom_bar_desc,
    GlassTuningScope.PILL_CONTROL to R.string.pref_glass_scope_pill_desc,
    GlassTuningScope.BOTTOM_PANEL to R.string.pref_glass_scope_bottom_panel_desc,
    GlassTuningScope.MENU to R.string.pref_glass_scope_menu_desc,
)

@Composable
private fun GlassRoleScope(
    scope: GlassTuningScope,
    tuning: GlassTuningState,
    onPreviewValue: (GlassTuningParam, Float) -> Unit,
    onSetValue: (GlassTuningScope, GlassTuningParam, Float) -> Unit,
    onSetFollowGlobal: (GlassTuningScope, GlassTuningParam, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Expose each role's preset state: what it is in the app (description)
    // plus how many parameters currently break away from the global baseline.
    val description = stringResource(
        roleDescriptions[scope] ?: R.string.pref_glass_follow_global,
    )
    val localOverrideCount = GlassTuning.paramsForScope(scope)
        .count { !tuning.isFollowingGlobal(scope, it) }
    val subtitle = if (localOverrideCount > 0) {
        description + " · " + stringResource(
            R.string.pref_glass_local_overrides_count,
            localOverrideCount,
        )
    } else {
        description
    }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        GlassScopeHeader(
            title = stringResource(roleTitles[scope] ?: R.string.pref_glass_scope_global),
            subtitle = subtitle,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                GlassTuning.paramsForScope(scope).forEach { param ->
                    val follow = tuning.isFollowingGlobal(scope, param)
                    GlassRoleParamRow(
                        scope = scope,
                        param = param,
                        value = tuning.value(scope, param),
                        follow = follow,
                        enabled = !follow,
                        onFollowChange = { onSetFollowGlobal(scope, param, it) },
                        onValuePreview = { onPreviewValue(param, it) },
                        onValueChange = { onSetValue(scope, param, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassRoleParamRow(
    scope: GlassTuningScope,
    param: GlassTuningParam,
    value: Float,
    follow: Boolean,
    enabled: Boolean,
    onFollowChange: (Boolean) -> Unit,
    onValuePreview: (Float) -> Unit,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = follow,
                    role = Role.Switch,
                    onValueChange = onFollowChange,
                )
                .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(paramTitleRes(param)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.pref_glass_follow_global),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = follow,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8f),
            )
        }
        if (follow) {
            Text(
                text = stringResource(
                    R.string.pref_glass_follow_global_summary_value,
                    formatParamValue(param, value),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
            )
        } else {
            GlassParamControl(
                param = param,
                value = value,
                enabled = enabled,
                onValuePreview = onValuePreview,
                onValueChange = onValueChange,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun GlassParamRow(
    param: GlassTuningParam,
    value: Float,
    enabled: Boolean,
    onValuePreview: (Float) -> Unit,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GlassParamControl(
            param = param,
            value = value,
            enabled = enabled,
            onValuePreview = onValuePreview,
            onValueChange = onValueChange,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassParamControl(
    param: GlassTuningParam,
    value: Float,
    enabled: Boolean,
    onValuePreview: (Float) -> Unit,
    onValueChange: (Float) -> Unit,
) {
    when (param.kind) {
        ParamKind.SWITCH -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = value >= 0.5f,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onValueChange(if (it) param.max else 0f) },
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(paramTitleRes(param)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(
                    checked = value >= 0.5f,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.scale(0.85f),
                )
            }
        }
        ParamKind.OPTION -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = stringResource(paramTitleRes(param)),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HighlightStyleLabel.entries.forEach { option ->
                        FilterChip(
                            selected = value.toInt() == option.value,
                            onClick = { onValueChange(option.value.toFloat()) },
                            enabled = enabled,
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
            }
        }
        ParamKind.SLIDER -> {
            var pendingValue by remember(value) { mutableStateOf(value) }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(paramTitleRes(param)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (enabled) formatParamValue(param, pendingValue) else "· · ·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = pendingValue.coerceIn(param.min, param.max),
                    onValueChange = {
                        pendingValue = it
                        onValuePreview(it)
                    },
                    onValueChangeFinished = { onValueChange(pendingValue) },
                    valueRange = param.min..param.max,
                    steps = ((param.max - param.min) / param.step).toInt() - 1,
                    enabled = enabled,
                )
            }
        }
    }
}

private enum class HighlightStyleLabel(val value: Int, val labelRes: Int) {
    DEFAULT(0, R.string.pref_glass_highlight_style_default),
    AMBIENT(1, R.string.pref_glass_highlight_style_ambient),
    PLAIN(2, R.string.pref_glass_highlight_style_plain),
}

private fun paramTitleRes(param: GlassTuningParam): Int = when (param) {
    GlassTuningParam.GLASS_ENABLED -> R.string.pref_glass_param_glass_enabled
    GlassTuningParam.VIBRANCY -> R.string.pref_glass_param_vibrancy
    GlassTuningParam.SATURATION -> R.string.pref_glass_param_saturation
    GlassTuningParam.BRIGHTNESS -> R.string.pref_glass_param_brightness
    GlassTuningParam.BLUR_RADIUS_DP -> R.string.pref_glass_param_blur_radius
    GlassTuningParam.LENS_HEIGHT_DP -> R.string.pref_glass_param_lens_height
    GlassTuningParam.LENS_AMOUNT_DP -> R.string.pref_glass_param_lens_amount
    GlassTuningParam.DEPTH_EFFECT -> R.string.pref_glass_param_depth_effect
    GlassTuningParam.CHROMATIC_ABERRATION -> R.string.pref_glass_param_chromatic_aberration
    GlassTuningParam.SURFACE_ALPHA -> R.string.pref_glass_param_surface_alpha
    GlassTuningParam.RIM_ENABLED -> R.string.pref_glass_param_rim
    GlassTuningParam.RIM_ALPHA -> R.string.pref_glass_param_rim_alpha
    GlassTuningParam.HIGHLIGHT_STYLE -> R.string.pref_glass_param_highlight_style
    GlassTuningParam.HAIRLINE_ENABLED -> R.string.pref_glass_param_hairline
    GlassTuningParam.HAIRLINE_ALPHA -> R.string.pref_glass_param_hairline_alpha
    GlassTuningParam.SHADOW_ENABLED -> R.string.pref_glass_param_shadow
    GlassTuningParam.SHADOW_RADIUS_DP -> R.string.pref_glass_param_shadow_radius
    GlassTuningParam.SHADOW_OFFSET_DP -> R.string.pref_glass_param_shadow_offset
    GlassTuningParam.SHADOW_ALPHA -> R.string.pref_glass_param_shadow_alpha
    GlassTuningParam.PRESS_HIGHLIGHT_ALPHA -> R.string.pref_glass_param_press_highlight
    GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP -> R.string.pref_glass_param_press_inner_radius
    GlassTuningParam.PRESS_INNER_SHADOW_ALPHA -> R.string.pref_glass_param_press_inner_alpha
    GlassTuningParam.PRESS_CHROMATIC_ABERRATION -> R.string.pref_glass_param_press_chromatic
    GlassTuningParam.PRESS_SCALE_PERCENT -> R.string.pref_glass_param_press_scale
    GlassTuningParam.PRESS_LENS_STRENGTH -> R.string.pref_glass_param_press_lens
}

@Composable
private fun formatParamValue(param: GlassTuningParam, value: Float): String = when (param) {
    GlassTuningParam.BLUR_RADIUS_DP,
    GlassTuningParam.LENS_HEIGHT_DP,
    GlassTuningParam.LENS_AMOUNT_DP,
    GlassTuningParam.SHADOW_RADIUS_DP,
    GlassTuningParam.SHADOW_OFFSET_DP,
    GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP,
    -> "${value.toInt()} dp"
    GlassTuningParam.PRESS_SCALE_PERCENT -> "${value.toInt()}%"
    GlassTuningParam.SURFACE_ALPHA,
    GlassTuningParam.RIM_ALPHA,
    GlassTuningParam.HAIRLINE_ALPHA,
    GlassTuningParam.SHADOW_ALPHA,
    GlassTuningParam.PRESS_HIGHLIGHT_ALPHA,
    GlassTuningParam.PRESS_INNER_SHADOW_ALPHA,
    GlassTuningParam.PRESS_LENS_STRENGTH,
    -> DecimalFormat("0.00").format(value)
    GlassTuningParam.SATURATION -> "${DecimalFormat("0.00").format(value)}×"
    GlassTuningParam.BRIGHTNESS -> if (value > 0f) {
        "+" + DecimalFormat("0.00").format(value)
    } else {
        DecimalFormat("0.00").format(value)
    }
    GlassTuningParam.HIGHLIGHT_STYLE -> stringResource(
        HighlightStyleLabel.entries.firstOrNull { it.value == value.toInt() }
            ?.labelRes ?: R.string.pref_glass_highlight_style_default,
    )
    else -> stringResource(if (value >= 0.5f) R.string.enabled else R.string.disabled)
}

/**
 * Quality presets. Applying one replaces the Global scope with these values and
 * makes every role follow global (see [GlassTuningController.applyPreset]),
 * except for roles listed in [roleOverrides] which use a "Global + delta"
 * config — the overridden keys resolve to the delta values, everything else
 * keeps following the preset's Global scope.
 */
enum class GlassPreset(
    val id: String,
    val titleRes: Int,
    val roleOverrides: Map<GlassTuningScope, Map<String, Float>> = emptyMap(),
) {
    CONTROL_CENTER(
        "control_center",
        R.string.pref_glass_preset_control_center,
        roleOverrides = mapOf(
            // ReaderMenuGlass fidelity: legado menus are flush surfaces with no
            // drop shadow — Control Center keeps its strong shadows on bars,
            // pills and panels but drops them inside menus.
            GlassTuningScope.MENU to mapOf(
                GlassTuningParam.SHADOW_ENABLED.key to 0f,
            ),
            // Small pill controls (compact tab rails, group pills, the selected
            // bottom-nav pill) are stadium shapes whose corner radius equals half
            // their shortest side — even a safety-clamped 20dp refraction ring
            // hugs the rounded caps and paints internal arc artifacts. Let pills
            // keep a gentle refraction while bars/panels keep the strong 24/24.
            GlassTuningScope.PILL_CONTROL to mapOf(
                GlassTuningParam.LENS_HEIGHT_DP.key to 8f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 12f,
            ),
        ),
    ),
    LIQUID("liquid", R.string.pref_glass_preset_liquid),
    SOFT("soft", R.string.pref_glass_preset_soft),
    CLEAN("clean", R.string.pref_glass_preset_clean),
    REFRACTION("refraction", R.string.pref_glass_preset_refraction),
    READER("reader", R.string.pref_glass_preset_reader),
    ECO("eco", R.string.pref_glass_preset_eco),
    VIBRANT("vibrant", R.string.pref_glass_preset_vibrant),
    DEPTH("depth", R.string.pref_glass_preset_depth),
    ;

    val config: GlassScopeConfig
        get() = GlassScopeConfig(
        values = when (this) {
            CONTROL_CENTER -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 4f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 24f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 24f,
                GlassTuningParam.DEPTH_EFFECT.key to 1f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.38f,
                GlassTuningParam.RIM_ENABLED.key to 1f,
                GlassTuningParam.RIM_ALPHA.key to 0.75f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 0f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 24f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 4f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.10f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0.15f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 4f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
            )
            LIQUID -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 8f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 16f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 24f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.40f,
                GlassTuningParam.RIM_ENABLED.key to 0f,
                GlassTuningParam.RIM_ALPHA.key to 0.65f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 0f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0f,
                GlassTuningParam.SHADOW_ENABLED.key to 0f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 4f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 2f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.10f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 1f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 1f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 8f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
            )
            SOFT -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 8f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 16f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 24f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.42f,
                GlassTuningParam.RIM_ENABLED.key to 0f,
                GlassTuningParam.RIM_ALPHA.key to 0.65f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.16f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 4f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 2f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.06f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 0.75f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0.8f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 6f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 0.8f,
            )
            CLEAN -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 4f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 0f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 0f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.30f,
                GlassTuningParam.RIM_ENABLED.key to 0f,
                GlassTuningParam.RIM_ALPHA.key to 0.5f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.20f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 2f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 0f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.04f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 0.5f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 0f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 4f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 0f,
            )
            REFRACTION -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 2f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 16f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 44f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.40f,
                GlassTuningParam.RIM_ENABLED.key to 0f,
                GlassTuningParam.RIM_ALPHA.key to 0.5f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.25f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 4f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 2f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.10f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0.8f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 6f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
            )
            READER -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 12f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 0f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 0f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.55f,
                GlassTuningParam.RIM_ENABLED.key to 1f,
                GlassTuningParam.RIM_ALPHA.key to 0.5f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.20f,
                GlassTuningParam.SHADOW_ENABLED.key to 0f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 0f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 0f,
                GlassTuningParam.SHADOW_ALPHA.key to 0f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 0.6f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 0f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 3f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 0f,
            )
            ECO -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 8f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 0f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 0f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.55f,
                GlassTuningParam.RIM_ENABLED.key to 1f,
                GlassTuningParam.RIM_ALPHA.key to 0.5f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.12f,
                GlassTuningParam.SHADOW_ENABLED.key to 0f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 0f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 0f,
                GlassTuningParam.SHADOW_ALPHA.key to 0f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 0.5f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 0f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 3f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 0f,
            )
            VIBRANT -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1.5f,
                GlassTuningParam.BRIGHTNESS.key to 0.05f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 12f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 10f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 14f,
                GlassTuningParam.DEPTH_EFFECT.key to 0f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 1f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.40f,
                GlassTuningParam.RIM_ENABLED.key to 1f,
                GlassTuningParam.RIM_ALPHA.key to 0.6f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0.15f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 4f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 2f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.10f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 1f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 1f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 6f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
            )
            DEPTH -> mapOf(
                GlassTuningParam.GLASS_ENABLED.key to 1f,
                GlassTuningParam.VIBRANCY.key to 1f,
                GlassTuningParam.SATURATION.key to 1f,
                GlassTuningParam.BRIGHTNESS.key to 0f,
                GlassTuningParam.BLUR_RADIUS_DP.key to 10f,
                GlassTuningParam.LENS_HEIGHT_DP.key to 20f,
                GlassTuningParam.LENS_AMOUNT_DP.key to 28f,
                GlassTuningParam.DEPTH_EFFECT.key to 1f,
                GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
                GlassTuningParam.SURFACE_ALPHA.key to 0.42f,
                GlassTuningParam.RIM_ENABLED.key to 1f,
                GlassTuningParam.RIM_ALPHA.key to 0.7f,
                GlassTuningParam.HIGHLIGHT_STYLE.key to 1f,
                GlassTuningParam.HAIRLINE_ENABLED.key to 0f,
                GlassTuningParam.HAIRLINE_ALPHA.key to 0f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
                GlassTuningParam.SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.SHADOW_OFFSET_DP.key to 3f,
                GlassTuningParam.SHADOW_ALPHA.key to 0.12f,
                GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
                GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
                GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 1f,
                GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 1f,
                GlassTuningParam.PRESS_SCALE_PERCENT.key to 8f,
                GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
            )
        },
        initialized = true,
    )

    fun matches(tuning: GlassTuningState): Boolean =
        GlassTuning.matches(tuning, config.values, roleOverrides)
}

// ---------------------------------------------------------------------------
// Live preview
// ---------------------------------------------------------------------------

private enum class PreviewBackdrop(
    val titleRes: Int,
    val dark: Boolean,
    val oled: Boolean,
    val solid: Color,
) {
    LIGHT(R.string.pref_glass_preview_backdrop_light, false, false, Color.White),
    GRAY(R.string.pref_glass_preview_backdrop_gray, false, false, Color(0xFFE9E9EC)),
    DARK(R.string.pref_glass_preview_backdrop_dark, true, false, Color(0xFF1B1C21)),
    OLED(R.string.pref_glass_preview_backdrop_oled, true, true, Color.Black),
}

@Composable
private fun GlassTuningPreview() {
    var backdrop by remember { mutableStateOf(PreviewBackdrop.LIGHT) }
    val scheme = if (backdrop.dark) darkColorScheme() else lightColorScheme()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PreviewBackdrop.entries.forEach { option ->
                FilterChip(
                    selected = backdrop == option,
                    onClick = { backdrop = option },
                    label = { Text(stringResource(option.titleRes)) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        // A dedicated layer backdrop so the preview stays live even though the
        // settings list itself runs with a null application backdrop. Follows
        // the canonical host pattern (SettingsTopBarScaffold / LiquidGlass-
        // BackdropHost): the host Box owns the non-glass background content,
        // records it into the layer, and the glass chrome is drawn on top of
        // that recorded layer. Hosting is required — without Modifier.layer-
        // Backdrop the layer has no coordinates and every glass surface in the
        // preview falls back to a flat tint (which in MD3 reads as options
        // bleeding through the preview).
        val previewBackdrop = rememberLayerBackdrop {
            drawRect(Color(0xFF7A7A80))
            drawContent()
        }
        CompositionLocalProvider(
            LocalLiquidGlassBackdrop provides previewBackdrop,
            LocalLiquidGlassLayerBackdrop provides previewBackdrop,
            LocalInterfaceStyle provides InterfaceStyle.IOS,
            LocalAmoledTheme provides backdrop.oled,
        ) {
            MaterialTheme(colorScheme = scheme) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(344.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    // Backdrop host: everything the mini chrome blurs lives
                    // here — the gradient wall and the solid bottom half. It is
                    // intentionally a sibling of the chrome (not an ancestor),
                    // so the recorded layer never contains the glass itself.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .layerBackdrop(previewBackdrop)
                            .background(
                                Brush.verticalGradient(
                                    if (backdrop.dark) {
                                        listOf(Color(0xFF38405C), Color(0xFF232736))
                                    } else {
                                        listOf(Color(0xFFFF8E7B), Color(0xFFFFC19A))
                                    },
                                ),
                                shape = RoundedCornerShape(22.dp),
                            ),
                    ) {
                        // Bottom half: a solid, switchable backdrop color.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(170.dp)
                                .background(backdrop.solid),
                        )
                    }
                    // The mini home snapshot: top bar, pill + menu, bottom panel,
                    // and a 64dp capsule bottom nav with a selected full-tab pill.
                    // Drawn after the host so the glass reads the recorded layer.
                    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                        PreviewTopBar()
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PreviewPillControl()
                            PreviewMenuCard(modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        PreviewBottomPanel()
                        Spacer(modifier = Modifier.height(10.dp))
                        PreviewBottomNav()
                    }
                    if (backdrop.oled) {
                        Text(
                            text = stringResource(R.string.pref_glass_preview_backdrop_oled),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTopBar() {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        style = GlassDefaults.topBarChromeStyle(),
        componentRole = GlassComponentRole.TopBar,
        shape = RoundedRectangle(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .height(10.dp)
                    .width(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PreviewPillControl() {
    // Pressable: the PillControl role is press-tracked by GlassSurface, so touch
    // live-animates the highlight / inner shadow / scale / chromatic lens.
    GlassSurface(
        modifier = Modifier.size(56.dp, 40.dp),
        style = GlassDefaults.prominentStyle(),
        componentRole = GlassComponentRole.PillControl,
        shape = Capsule(),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PreviewMenuCard(modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier.height(56.dp),
        style = GlassDefaults.prominentStyle(),
        componentRole = GlassComponentRole.Menu,
        shape = RoundedRectangle(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun PreviewBottomPanel() {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(46.dp),
        style = GlassDefaults.prominentStyle(),
        componentRole = GlassComponentRole.BottomPanel,
        shape = RoundedRectangle(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(110.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)),
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(Capsule())
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun PreviewBottomNav() {
    val items = listOf(
        R.drawable.ic_home_filled,
        R.drawable.ic_history,
        R.drawable.ic_explore_normal,
    )
    Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            style = GlassDefaults.bottomBarChromeStyle(),
            componentRole = GlassComponentRole.BottomBar,
            shape = Capsule(),
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                items.forEachIndexed { index, iconRes ->
                    val selected = index == 0
                    if (selected) {
                        // Selected tab: a pressable full-tab glass pill.
                        GlassSurface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(4.dp),
                            style = GlassDefaults.bottomBarChromeStyle(),
                            componentRole = GlassComponentRole.PillControl,
                            shape = Capsule(),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
