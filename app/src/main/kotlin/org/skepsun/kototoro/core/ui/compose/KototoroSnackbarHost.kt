package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * The app's snackbar host.
 *
 * Material3 paints a snackbar with `inverseSurface` — a near-white plate that reads as a snackbar
 * on a phone but lands as a blinding white card over the dark glass TV chrome. On TV the host takes
 * a colour from the surface ramp instead, so a tip or an error stays inside the same visual language
 * as the rest of the screen. Off TV this is exactly the stock [SnackbarHost].
 *
 * The TV branch also rebuilds the content instead of handing [SnackbarHost]'s data straight to the
 * stock overload: the stock message is `bodyMedium` (14sp), which is a phone reading distance, and
 * the stock action label is `labelLarge` in the same size. On a television both are bumped one step
 * and the plate is given a taller minimum so the text is not squeezed against the rounded corners.
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
                modifier = Modifier.heightIn(min = 72.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionContentColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                action = data.visuals.actionLabel?.let { label ->
                    {
                        TextButton(
                            onClick = data::performAction,
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(10.dp),
                                addFocusTarget = false,
                            ),
                        ) {
                            Text(text = label, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (data.visuals.withDismissAction) {
                        IconButton(
                            onClick = data::dismiss,
                            modifier = Modifier.tvFocusable(
                                shape = CircleShape,
                                addFocusTarget = false,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            Snackbar(snackbarData = data)
        }
    }
}
