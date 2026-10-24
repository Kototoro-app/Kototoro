package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * The app's snackbar host.
 *
 * Material3 paints a snackbar with `inverseSurface` — a near-white plate that reads as a snackbar
 * on a phone but lands as a blinding white card over the dark glass TV chrome. On TV the host takes
 * a colour from the surface ramp instead, so a tip or an error stays inside the same visual language
 * as the rest of the screen. Off TV this is exactly the stock [SnackbarHost].
 */
@Composable
fun KototoroSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        if (isTvPresentation) {
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            Snackbar(snackbarData = data)
        }
    }
}
