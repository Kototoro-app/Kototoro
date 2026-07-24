package org.skepsun.kototoro.video.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.video.dlna.DlnaDevice

internal sealed interface DlnaDeviceDialogState {
	data object Loading : DlnaDeviceDialogState
	data class Devices(val devices: List<DlnaDevice>) : DlnaDeviceDialogState
	data class Casting(val device: DlnaDevice) : DlnaDeviceDialogState
}

@Composable
internal fun DlnaDeviceDialog(
	state: DlnaDeviceDialogState,
	onDismissRequest: () -> Unit,
	onDeviceSelected: (DlnaDevice) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismissRequest,
		title = { Text(stringResource(R.string.cast_to_device)) },
		text = {
			when (state) {
				DlnaDeviceDialogState.Loading -> LoadingContent()
				is DlnaDeviceDialogState.Casting -> LoadingContent(state.device.name)
				is DlnaDeviceDialogState.Devices -> {
					if (state.devices.isEmpty()) {
						Text(stringResource(R.string.no_dlna_devices_found))
					} else {
						LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
							items(state.devices, key = DlnaDevice::location) { device ->
								Text(
									text = device.name,
									modifier = Modifier
										.fillMaxWidth()
										.clickable { onDeviceSelected(device) }
										.padding(vertical = 14.dp),
								)
							}
						}
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismissRequest) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

@Composable
private fun LoadingContent(label: String? = null) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
		Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
			CircularProgressIndicator()
		}
		label?.let { Text(it) }
	}
}
