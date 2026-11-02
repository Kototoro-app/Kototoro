package org.skepsun.kototoro.video.ui.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton

@Composable
internal fun VideoPlayerNativeInitErrorDialog(onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.error_occurred)) },
        text = { Text(stringResource(R.string.video_player_native_init_failed)) },
        confirmButton = {
            TvTextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
