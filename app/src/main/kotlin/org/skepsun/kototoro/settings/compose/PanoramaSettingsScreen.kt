package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

data class PanoramaSettingsUiState(
    val enabled: Boolean,
    val layoutMode: PanoramaLayoutMode,
    val preset: PanoramaEffectPreset,
    val blurPercent: Int,
    val transitionRangePercent: Int,
    val topOpacityPercent: Int,
    val animationEnabled: Boolean,
    val animationSettingsEnabled: Boolean,
    val scrollLinked: Boolean,
)

@Composable
fun PanoramaSettingsScreen(
    state: PanoramaSettingsUiState,
    modifier: Modifier = Modifier,
    onEnabledChange: (Boolean) -> Unit,
    onLayoutModeChange: (PanoramaLayoutMode) -> Unit,
    onPresetChange: (PanoramaEffectPreset) -> Unit,
    onScrollLinkedChange: (Boolean) -> Unit,
    onAnimationEnabledChange: (Boolean) -> Unit,
    onBlurChange: (Int) -> Unit,
    onTransitionRangeChange: (Int) -> Unit,
    onTopOpacityChange: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(8.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "effect") {
                SettingsPreferenceGroup(title = stringResource(R.string.panorama_settings_effect)) {
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.pref_panorama_cover),
                            iconRes = R.drawable.ic_images,
                            checked = state.enabled,
                            summary = stringResource(R.string.pref_panorama_cover_summary),
                            onCheckedChange = onEnabledChange,
                        )
                    }
                    item {
                        PanoramaLayoutModePreference(
                            selected = state.layoutMode,
                            enabled = state.enabled,
                            onSelected = onLayoutModeChange,
                        )
                    }
                    item {
                        PanoramaPresetPreference(
                            selected = state.preset,
                            enabled = state.enabled,
                            onSelected = onPresetChange,
                        )
                    }
                }
            }
            item(key = "behavior") {
                SettingsPreferenceGroup(title = stringResource(R.string.panorama_settings_behavior)) {
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.pref_details_panorama_scroll_linked),
                            iconRes = R.drawable.ic_sync,
                            checked = state.scrollLinked,
                            summary = stringResource(R.string.pref_details_panorama_scroll_linked_summary),
                            enabled = state.enabled && state.layoutMode == PanoramaLayoutMode.HALF_SCREEN,
                            onCheckedChange = onScrollLinkedChange,
                        )
                    }
                    item {
                        SettingsSwitchPreference(
                            title = stringResource(R.string.pref_panorama_animation),
                            iconRes = R.drawable.ic_animation,
                            checked = state.animationEnabled,
                            summary = stringResource(
                                if (state.animationSettingsEnabled) {
                                    R.string.pref_panorama_animation_summary
                                } else {
                                    R.string.panorama_animation_reduced_effects_summary
                                },
                            ),
                            enabled = state.enabled && state.animationSettingsEnabled,
                            onCheckedChange = onAnimationEnabledChange,
                        )
                    }
                }
            }
            item(key = "advanced") {
                SettingsCollapsiblePreferenceGroup(
                    title = stringResource(R.string.panorama_settings_advanced),
                    initiallyExpanded = false,
                ) {
                    item {
                        SettingsSliderPreference(
                            title = stringResource(R.string.pref_panorama_blur),
                            iconRes = R.drawable.ic_eye_off,
                            value = state.blurPercent,
                            valueRange = 0..100,
                            step = 5,
                            enabled = state.enabled,
                            valueText = { "$it%" },
                            onValueChange = onBlurChange,
                        )
                    }
                    item {
                        SettingsSliderPreference(
                            title = stringResource(R.string.pref_panorama_top_opacity),
                            iconRes = R.drawable.ic_eye,
                            value = state.topOpacityPercent,
                            valueRange = 0..100,
                            step = 5,
                            summary = stringResource(R.string.pref_panorama_top_opacity_summary),
                            enabled = state.enabled,
                            valueText = { "$it%" },
                            onValueChange = onTopOpacityChange,
                        )
                    }
                    item {
                        SettingsSliderPreference(
                            title = stringResource(R.string.pref_panorama_transition_intensity),
                            iconRes = R.drawable.ic_timelapse,
                            value = state.transitionRangePercent,
                            valueRange = 0..100,
                            step = 5,
                            summary = stringResource(R.string.pref_panorama_transition_intensity_summary),
                            enabled = state.enabled,
                            valueText = { "$it%" },
                            onValueChange = onTransitionRangeChange,
                        )
                    }
                    item {
                        SettingsActionPreference(
                            title = stringResource(R.string.panorama_settings_restore_default),
                            iconRes = R.drawable.ic_revert,
                            enabled = state.enabled,
                            showChevron = false,
                            onClick = onReset,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanoramaLayoutModePreference(
    selected: PanoramaLayoutMode,
    enabled: Boolean,
    onSelected: (PanoramaLayoutMode) -> Unit,
) {
    val modes = PanoramaLayoutMode.entries
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.panorama_settings_layout_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    PanoramaLayoutMode.FULL_SCREEN -> R.string.panorama_mode_full_screen
                                    PanoramaLayoutMode.HALF_SCREEN -> R.string.panorama_mode_half_screen
                                },
                            ),
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
private fun PanoramaPresetPreference(
    selected: PanoramaEffectPreset,
    enabled: Boolean,
    onSelected: (PanoramaEffectPreset) -> Unit,
) {
    val presets = PanoramaEffectPreset.entries
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.panorama_settings_style),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            presets.forEachIndexed { index, preset ->
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected == preset,
                    onClick = { if (preset != PanoramaEffectPreset.CUSTOM) onSelected(preset) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                    label = {
                        Text(
                            text = panoramaPresetLabel(preset),
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
private fun panoramaPresetLabel(preset: PanoramaEffectPreset): String = stringResource(
    when (preset) {
        PanoramaEffectPreset.CLEAR -> R.string.panorama_preset_clear
        PanoramaEffectPreset.BALANCED -> R.string.panorama_preset_balanced
        PanoramaEffectPreset.SOFT -> R.string.panorama_preset_soft
        PanoramaEffectPreset.CUSTOM -> R.string.panorama_preset_custom
    },
)
