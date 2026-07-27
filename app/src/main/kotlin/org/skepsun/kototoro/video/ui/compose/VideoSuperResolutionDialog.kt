package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader

internal data class VideoShaderOption(
	val fileName: String,
	val description: String?,
	val selected: Boolean,
)

internal data class VideoSuperResolutionDialogState(
	val selectedMode: VideoSuperResolutionMode,
	val selectedShader: VideoSuperResolutionShader,
	val shaderLabels: Map<VideoSuperResolutionShader, String>,
	val customShaders: List<VideoShaderOption>,
	val anchorBounds: IntRect = IntRect.Zero,
)

@Composable
internal fun VideoSuperResolutionDialog(
	state: VideoSuperResolutionDialogState,
	onDismissRequest: () -> Unit,
	onModeSelected: (VideoSuperResolutionMode) -> Unit,
	onShaderSelected: (VideoSuperResolutionShader) -> Unit,
	onCustomShaderToggled: (String, Boolean) -> Unit,
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
					text = stringResource(R.string.video_super_resolution),
					style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
				)
				VideoSuperResolutionMode.entries.forEach { mode ->
					SelectionRow(
						label = superResolutionModeLabel(mode),
						selected = mode == state.selectedMode,
						onClick = { onModeSelected(mode) },
					)
				}
				if (state.selectedMode != VideoSuperResolutionMode.OFF) {
					HorizontalDivider(
						modifier = Modifier.padding(vertical = 8.dp),
						color = Color.White.copy(alpha = 0.14f),
					)
					Text(stringResource(R.string.video_super_resolution_submode_format, ""))
					VideoSuperResolutionShader.entries.forEach { shader ->
						SelectionRow(
							label = state.shaderLabels.getValue(shader),
							selected = shader == state.selectedShader,
							onClick = { onShaderSelected(shader) },
						)
					}
				}
				if (state.selectedMode == VideoSuperResolutionMode.ADVANCED ||
					state.selectedShader == VideoSuperResolutionShader.CUSTOM
				) {
					HorizontalDivider(
						modifier = Modifier.padding(vertical = 8.dp),
						color = Color.White.copy(alpha = 0.14f),
					)
					state.customShaders.forEach { shader ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onCustomShaderToggled(shader.fileName, !shader.selected) }
								.padding(vertical = 6.dp),
						) {
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = shader.fileName,
									style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
								)
								shader.description?.let {
									Text(
										text = it,
										style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
										color = Color.White.copy(alpha = 0.70f),
									)
								}
							}
							Switch(
								checked = shader.selected,
								onCheckedChange = { onCustomShaderToggled(shader.fileName, it) },
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
				}
			}
		}
	}
}

@Composable
private fun SelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
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

@Composable
private fun superResolutionModeLabel(mode: VideoSuperResolutionMode): String = stringResource(
	when (mode) {
		VideoSuperResolutionMode.OFF -> R.string.video_super_resolution_off
		VideoSuperResolutionMode.QUALITY -> R.string.video_super_resolution_quality
		VideoSuperResolutionMode.BALANCED -> R.string.video_super_resolution_balanced
		VideoSuperResolutionMode.PERFORMANCE -> R.string.video_super_resolution_performance
		VideoSuperResolutionMode.ADVANCED -> R.string.video_super_resolution_advanced
	},
)
