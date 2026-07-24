package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
)

@Composable
internal fun VideoSuperResolutionDialog(
	state: VideoSuperResolutionDialogState,
	onDismissRequest: () -> Unit,
	onModeSelected: (VideoSuperResolutionMode) -> Unit,
	onShaderSelected: (VideoSuperResolutionShader) -> Unit,
	onCustomShaderToggled: (String, Boolean) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(R.string.video_super_resolution)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 560.dp)
					.verticalScroll(rememberScrollState()),
			) {
				VideoSuperResolutionMode.entries.forEach { mode ->
					SelectionRow(
						label = superResolutionModeLabel(mode),
						selected = mode == state.selectedMode,
						onClick = { onModeSelected(mode) },
					)
				}
				if (state.selectedMode != VideoSuperResolutionMode.OFF) {
					HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
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
					HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
					state.customShaders.forEach { shader ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onCustomShaderToggled(shader.fileName, !shader.selected) }
								.padding(vertical = 6.dp),
						) {
							Column(modifier = Modifier.weight(1f)) {
								Text(shader.fileName)
								shader.description?.let { Text(it) }
							}
							Switch(
								checked = shader.selected,
								onCheckedChange = { onCustomShaderToggled(shader.fileName, it) },
							)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismissRequest) {
				Text(stringResource(android.R.string.ok))
			}
		},
	)
}

@Composable
private fun SelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
	) {
		RadioButton(selected = selected, onClick = onClick)
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
