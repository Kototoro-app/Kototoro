package org.skepsun.kototoro.video.ui.compose

import android.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvSliderDpad
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus
import org.skepsun.kototoro.core.ui.compose.KototoroSlider

internal data class VideoSubtitleSettingsDialogState(
    val fontSizeSp: Float,
    val bold: Boolean,
    val italic: Boolean,
    val fontFamily: SubtitleFontFamily,
    val textColor: Int,
    val borderColor: Int,
    val borderSize: Float,
    val backgroundColor: Int,
    val backgroundStyle: SubtitleBackgroundStyle,
    val backgroundCornerRadiusDp: Int,
    val backgroundPaddingHorizontalDp: Int,
    val backgroundPaddingVerticalDp: Int,
    val alignX: Int,
    val position: Int,
    val subtitleTrackOptions: List<String> = emptyList(),
    val subtitleTrackSelectedIndex: Int = 0,
    val anchorBounds: IntRect = IntRect.Zero,
)

private enum class SubtitleSettingsTab {
    Tracks,
    Typography,
    Background,
    Layout,
    Colors,
}

private enum class SubtitleColorTarget(val id: Int) {
    Text(0),
    Border(1),
    Background(2),
}

@Composable
internal fun VideoSubtitleSettingsDialog(
    state: VideoSubtitleSettingsDialogState,
    onDismissRequest: () -> Unit,
    onStyleChanged: (VideoSubtitleSettingsDialogState) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val maxHeight = (
        screenHeight -
            safeDrawingPadding.calculateTopPadding() -
            safeDrawingPadding.calculateBottomPadding() -
            16.dp
        ).coerceAtLeast(240.dp)

    var tab by remember { mutableStateOf(SubtitleSettingsTab.Tracks) }
    var colorTarget by remember { mutableStateOf(SubtitleColorTarget.Text) }
    var style by remember { mutableStateOf(state) }

    val emit = { new: VideoSubtitleSettingsDialogState -> style = new; onStyleChanged(new) }

    val isTv = LocalUiPresentationConfig.current.isTv
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
        val (settingsMinWidth, settingsMaxWidth) = playerMenuWidth(phoneMin = 300.dp, phoneMax = 380.dp)
        Surface(
            modifier = Modifier
                .widthIn(min = settingsMinWidth, max = settingsMaxWidth)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(18.dp),
            color = playerMenuContainerColor(phoneAlpha = 0.90f),
            contentColor = ComposeColor.White,
            border = BorderStroke(1.dp, ComposeColor.White.copy(alpha = 0.16f)),
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .tvTakeInitialFocus(),
            ) {
                Text(
                    text = stringResource(R.string.video_subtitle_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ComposeColor.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    SubtitleSettingsTab.entries.forEach { t ->
                        SubtitleChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(tabLabel(t)) },
                            colors = playerChipColors(),
                        )
                    }
                }

                if (tab != SubtitleSettingsTab.Tracks) {
                    Text(
                        text = stringResource(R.string.video_subtitle_style),
                        style = MaterialTheme.typography.labelLarge,
                        color = ComposeColor.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        SubtitleChip(
                            selected = style.bold,
                            onClick = { emit(style.copy(bold = !style.bold)) },
                            label = { Text("B", fontWeight = FontWeight.Bold) },
                            colors = playerChipColors(),
                        )
                        SubtitleChip(
                            selected = style.italic,
                            onClick = { emit(style.copy(italic = !style.italic)) },
                            label = { Text("I", fontStyle = FontStyle.Italic) },
                            colors = playerChipColors(),
                        )
                    }
                }

                when (tab) {
                    SubtitleSettingsTab.Tracks -> {
                        if (state.subtitleTrackOptions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.video_no_subtitle_tracks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ComposeColor.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else {
                            state.subtitleTrackOptions.forEachIndexed { index, label ->
                                SubtitleChip(
                                    selected = state.subtitleTrackSelectedIndex == index,
                                    onClick = { onSubtitleTrackSelected(index) },
                                    label = { Text(label) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    colors = playerChipColors(),
                                )
                            }
                        }
                    }

                    SubtitleSettingsTab.Typography -> {
                        Text(
                            text = stringResource(R.string.video_subtitle_font_family),
                            style = MaterialTheme.typography.labelLarge,
                            color = ComposeColor.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            SubtitleFontFamily.entries.forEach { family ->
                                SubtitleChip(
                                    selected = style.fontFamily == family,
                                    onClick = { emit(style.copy(fontFamily = family)) },
                                    label = { Text(fontFamilyLabel(family)) },
                                    colors = playerChipColors(),
                                )
                            }
                        }
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_font_size),
                            valueText = style.fontSizeSp.toInt().toString(),
                            value = style.fontSizeSp,
                            valueRange = 10f..100f,
                            onValueChange = { emit(style.copy(fontSizeSp = it)) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_border_size),
                            valueText = style.borderSize.toInt().toString(),
                            value = style.borderSize,
                            valueRange = 0f..24f,
                            onValueChange = { emit(style.copy(borderSize = it)) },
                        )
                    }

                    SubtitleSettingsTab.Background -> {
                        Text(
                            text = stringResource(R.string.video_subtitle_background_style),
                            style = MaterialTheme.typography.labelLarge,
                            color = ComposeColor.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            SubtitleBackgroundStyle.entries.forEach { backgroundStyle ->
                                SubtitleChip(
                                    selected = style.backgroundStyle == backgroundStyle,
                                    onClick = { emit(style.copy(backgroundStyle = backgroundStyle)) },
                                    label = { Text(backgroundStyleLabel(backgroundStyle)) },
                                    colors = playerChipColors(),
                                )
                            }
                        }
                        val backgroundEnabled = style.backgroundStyle != SubtitleBackgroundStyle.NONE
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_background_corner_radius),
                            valueText = style.backgroundCornerRadiusDp.toString(),
                            value = style.backgroundCornerRadiusDp.toFloat(),
                            valueRange = 0f..32f,
                            enabled = backgroundEnabled,
                            onValueChange = { emit(style.copy(backgroundCornerRadiusDp = it.toInt())) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_background_padding_horizontal),
                            valueText = style.backgroundPaddingHorizontalDp.toString(),
                            value = style.backgroundPaddingHorizontalDp.toFloat(),
                            valueRange = 0f..32f,
                            enabled = backgroundEnabled,
                            onValueChange = { emit(style.copy(backgroundPaddingHorizontalDp = it.toInt())) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_background_padding_vertical),
                            valueText = style.backgroundPaddingVerticalDp.toString(),
                            value = style.backgroundPaddingVerticalDp.toFloat(),
                            valueRange = 0f..24f,
                            enabled = backgroundEnabled,
                            onValueChange = { emit(style.copy(backgroundPaddingVerticalDp = it.toInt())) },
                        )
                        if (backgroundEnabled) {
                            Text(
                                text = stringResource(R.string.video_subtitle_background_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = ComposeColor.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }

                    SubtitleSettingsTab.Layout -> {
                        Text(
                            text = stringResource(R.string.video_subtitle_position),
                            style = MaterialTheme.typography.labelLarge,
                            color = ComposeColor.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            SubtitleChip(
                                selected = style.alignX == 0,
                                onClick = { emit(style.copy(alignX = 0)) },
                                label = { Text(stringResource(R.string.video_subtitle_align_left)) },
                                colors = playerChipColors(),
                            )
                            SubtitleChip(
                                selected = style.alignX == 1,
                                onClick = { emit(style.copy(alignX = 1)) },
                                label = { Text(stringResource(R.string.video_subtitle_align_center)) },
                                colors = playerChipColors(),
                            )
                            SubtitleChip(
                                selected = style.alignX == 2,
                                onClick = { emit(style.copy(alignX = 2)) },
                                label = { Text(stringResource(R.string.video_subtitle_align_right)) },
                                colors = playerChipColors(),
                            )
                        }
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_position),
                            valueText = style.position.toString(),
                            value = style.position.toFloat(),
                            valueRange = 0f..300f,
                            onValueChange = { emit(style.copy(position = it.toInt())) },
                        )
                    }

                    SubtitleSettingsTab.Colors -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            SubtitleColorTarget.entries.forEach { target ->
                                SubtitleChip(
                                    selected = colorTarget == target,
                                    onClick = { colorTarget = target },
                                    label = { Text(colorTargetLabel(target)) },
                                    colors = playerChipColors(),
                                )
                            }
                        }
                        val currentColor = when (colorTarget) {
                            SubtitleColorTarget.Text -> style.textColor
                            SubtitleColorTarget.Border -> style.borderColor
                            SubtitleColorTarget.Background -> style.backgroundColor
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            SubtitleColorTarget.entries.forEach { target ->
                                val color = when (target) {
                                    SubtitleColorTarget.Text -> style.textColor
                                    SubtitleColorTarget.Border -> style.borderColor
                                    SubtitleColorTarget.Background -> style.backgroundColor
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = if (isTv) 56.dp else 28.dp)
                                        .tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false)
                                        .clickable { colorTarget = target },
                                    shape = RoundedCornerShape(8.dp),
                                    color = ComposeColor(color),
                                    border = if (colorTarget == target) {
                                        BorderStroke(2.dp, ComposeColor.White)
                                    } else {
                                        BorderStroke(1.dp, ComposeColor.White.copy(alpha = 0.4f))
                                    },
                                ) {}
                            }
                        }
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_color_alpha),
                            valueText = Color.alpha(currentColor).toString(),
                            value = Color.alpha(currentColor).toFloat(),
                            valueRange = 0f..255f,
                            onValueChange = { emit(updateColor(style, colorTarget, it.toInt(), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_color_red),
                            valueText = Color.red(currentColor).toString(),
                            value = Color.red(currentColor).toFloat(),
                            valueRange = 0f..255f,
                            accent = ComposeColor(0xFFFF0000),
                            onValueChange = { emit(updateColor(style, colorTarget, Color.alpha(currentColor), it.toInt(), Color.green(currentColor), Color.blue(currentColor))) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_color_green),
                            valueText = Color.green(currentColor).toString(),
                            value = Color.green(currentColor).toFloat(),
                            valueRange = 0f..255f,
                            accent = ComposeColor(0xFF00FF00),
                            onValueChange = { emit(updateColor(style, colorTarget, Color.alpha(currentColor), Color.red(currentColor), it.toInt(), Color.blue(currentColor))) },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.video_subtitle_color_blue),
                            valueText = Color.blue(currentColor).toString(),
                            value = Color.blue(currentColor).toFloat(),
                            valueRange = 0f..255f,
                            accent = ComposeColor(0xFF0000FF),
                            onValueChange = { emit(updateColor(style, colorTarget, Color.alpha(currentColor), Color.red(currentColor), Color.green(currentColor), it.toInt())) },
                        )
                    }
                }
            }
        }
    }
}

private fun updateColor(
    style: VideoSubtitleSettingsDialogState,
    target: SubtitleColorTarget,
    a: Int, r: Int, g: Int, b: Int,
): VideoSubtitleSettingsDialogState {
    val color = Color.argb(a, r, g, b)
    return when (target) {
        SubtitleColorTarget.Text -> style.copy(textColor = color)
        SubtitleColorTarget.Border -> style.copy(borderColor = color)
        SubtitleColorTarget.Background -> style.copy(backgroundColor = color)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    accent: ComposeColor = ComposeColor.White,
) {
    val steps = (valueRange.endInclusive - valueRange.start).toInt() - 1
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ComposeColor.White.copy(alpha = if (enabled) 0.85f else 0.4f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = ComposeColor.White.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
        KototoroSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .tvSliderDpad(
                    value = value,
                    valueRange = valueRange,
                    onValueChange = onValueChange,
                    steps = steps,
                    enabled = enabled,
                ),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = ComposeColor.White.copy(alpha = 0.3f),
            ),
        )
    }
}

/**
 * A filter chip that a remote can actually reach.
 *
 * A Material3 [FilterChip] only shows a focus state layer, which on a 1080p screen from three metres
 * away is indistinguishable from "nothing is selected". The TV branch draws the same pill by hand so
 * it can carry a focus ring and a row height that matches the rest of the player menu; the phone
 * keeps the stock chip.
 */
@Composable
private fun SubtitleChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: SelectableChipColors = playerChipColors(),
    label: @Composable () -> Unit,
) {
    if (!LocalUiPresentationConfig.current.isTv) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            colors = colors,
        )
        return
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = playerMenuRowMinHeight(32.dp))
            .clip(shape)
            .background(
                color = if (selected) ComposeColor.White else ComposeColor.White.copy(alpha = 0.12f),
                shape = shape,
            )
            .tvFocusable(shape = shape, addFocusTarget = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) ComposeColor.Black else ComposeColor.White,
        ) {
            label()
        }
    }
}

@Composable
private fun playerChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = ComposeColor.White.copy(alpha = 0.10f),
    labelColor = ComposeColor.White,
    selectedContainerColor = ComposeColor.White,
    selectedLabelColor = ComposeColor.Black,
)

@Composable
private fun tabLabel(tab: SubtitleSettingsTab): String = stringResource(
    when (tab) {
        SubtitleSettingsTab.Tracks -> R.string.video_subtitle_track
        SubtitleSettingsTab.Typography -> R.string.video_subtitle_style
        SubtitleSettingsTab.Background -> R.string.video_subtitle_background
        SubtitleSettingsTab.Layout -> R.string.video_subtitle_layout
        SubtitleSettingsTab.Colors -> R.string.video_subtitle_colors
    },
)

@Composable
private fun backgroundStyleLabel(style: SubtitleBackgroundStyle): String = stringResource(
    when (style) {
        SubtitleBackgroundStyle.NONE -> R.string.video_subtitle_background_none
        SubtitleBackgroundStyle.BAR -> R.string.video_subtitle_background_bar
        SubtitleBackgroundStyle.BLOCK -> R.string.video_subtitle_background_block
        SubtitleBackgroundStyle.LINES -> R.string.video_subtitle_background_lines
    },
)

@Composable
private fun fontFamilyLabel(family: SubtitleFontFamily): String = stringResource(
    when (family) {
        SubtitleFontFamily.SANS -> R.string.video_subtitle_font_sans
        SubtitleFontFamily.SERIF -> R.string.video_subtitle_font_serif
        SubtitleFontFamily.MONOSPACE -> R.string.video_subtitle_font_monospace
    },
)

@Composable
private fun colorTargetLabel(target: SubtitleColorTarget): String = stringResource(
    when (target) {
        SubtitleColorTarget.Text -> R.string.video_subtitle_color_text
        SubtitleColorTarget.Border -> R.string.video_subtitle_color_border
        SubtitleColorTarget.Background -> R.string.video_subtitle_color_bg
    },
)
