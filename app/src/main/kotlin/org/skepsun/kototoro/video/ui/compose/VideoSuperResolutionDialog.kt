package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.Anime4KPreset
import org.skepsun.kototoro.core.prefs.VideoEnhancementAlgorithm

internal data class VideoSuperResolutionDialogState(
    val enabled: Boolean,
    val algorithm: VideoEnhancementAlgorithm,
    val anime4KPreset: Anime4KPreset,
    val fsrSharpnessPercent: Int,
    val rememberAcrossVideos: Boolean,
    val anchorBounds: IntRect = IntRect.Zero,
)

@Composable
internal fun VideoSuperResolutionDialog(
    state: VideoSuperResolutionDialogState,
    onDismissRequest: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAlgorithmSelected: (VideoEnhancementAlgorithm) -> Unit,
    onAnime4KPresetSelected: (Anime4KPreset) -> Unit,
    onFsrSharpnessChanged: (Int) -> Unit,
    onRememberAcrossVideosChanged: (Boolean) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.76f).dp
    Popup(
        popupPositionProvider = PlayerMenuPositionProvider(
            targetBounds = state.anchorBounds,
            placement = PlayerMenuPlacement.BesideAnchor,
            gapPx = gapPx,
            marginPx = marginPx,
        ),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 300.dp, max = 380.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.90f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.ai_video_enhancement_settings),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                SwitchRow(
                    label = stringResource(R.string.video_enhancement_enabled),
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                )
                if (state.enabled) {
                    SectionDivider()
                    SectionTitle(stringResource(R.string.video_enhancement_algorithm))
                    SelectionRow(
                        label = stringResource(R.string.video_enhancement_algorithm_anime4k),
                        selected = state.algorithm == VideoEnhancementAlgorithm.ANIME4K,
                        onClick = { onAlgorithmSelected(VideoEnhancementAlgorithm.ANIME4K) },
                    )
                    SelectionRow(
                        label = stringResource(R.string.video_enhancement_algorithm_fsr),
                        selected = state.algorithm == VideoEnhancementAlgorithm.FSR_1_0,
                        onClick = { onAlgorithmSelected(VideoEnhancementAlgorithm.FSR_1_0) },
                    )
                    SectionDivider()
                    when (state.algorithm) {
                        VideoEnhancementAlgorithm.ANIME4K -> Anime4KPresetSection(
                            selectedPreset = state.anime4KPreset,
                            onPresetSelected = onAnime4KPresetSelected,
                        )
                        VideoEnhancementAlgorithm.FSR_1_0 -> FsrSharpnessSection(
                            sharpnessPercent = state.fsrSharpnessPercent,
                            onSharpnessChanged = onFsrSharpnessChanged,
                        )
                    }
                }
                SectionDivider()
                SwitchRow(
                    label = stringResource(R.string.video_enhancement_remember),
                    checked = state.rememberAcrossVideos,
                    onCheckedChange = onRememberAcrossVideosChanged,
                )
                Text(
                    text = stringResource(R.string.video_enhancement_power_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.68f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Anime4KPresetSection(
    selectedPreset: Anime4KPreset,
    onPresetSelected: (Anime4KPreset) -> Unit,
) {
    SectionTitle(stringResource(R.string.video_enhancement_anime4k_preset))
    SelectionRow(
        label = stringResource(R.string.video_enhancement_anime4k_fast),
        selected = selectedPreset == Anime4KPreset.FAST,
        onClick = { onPresetSelected(Anime4KPreset.FAST) },
    )
    SelectionRow(
        label = stringResource(R.string.video_enhancement_anime4k_quality),
        selected = selectedPreset == Anime4KPreset.QUALITY,
        onClick = { onPresetSelected(Anime4KPreset.QUALITY) },
    )
}

@Composable
private fun FsrSharpnessSection(sharpnessPercent: Int, onSharpnessChanged: (Int) -> Unit) {
    SectionTitle(stringResource(R.string.video_enhancement_fsr_sharpness, sharpnessPercent))
    Slider(
        value = sharpnessPercent.toFloat(),
        onValueChange = { value -> onSharpnessChanged((value / 10f).roundToInt() * 10) },
        valueRange = 0f..100f,
        steps = 9,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.70f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = Color.White.copy(alpha = 0.14f),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.72f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.20f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.38f),
            ),
        )
    }
}

@Composable
private fun SelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color.White,
                unselectedColor = Color.White.copy(alpha = 0.66f),
            ),
        )
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
