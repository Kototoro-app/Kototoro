package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.glass.ApplyDynamicArtworkBlurDialogStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

@Composable
internal fun SettingsAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val colors = MaterialTheme.colorScheme
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val bodyFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    var bodyHasFocus by remember { mutableStateOf(false) }

    if (isTvPresentation) {
        // A dialog opens its own window, so nothing is focused unless someone asks. Without this the
        // remote either lands nowhere or keeps walking the dimmed screen behind the dialog. Prefer the
        // body - call sites put pickers there - and fall back to the confirm action when the body has
        // no focusable child to offer.
        LaunchedEffect(Unit) {
            repeat(6) {
                withFrameNanos { }
                if (bodyHasFocus) return@LaunchedEffect
                runCatching { bodyFocusRequester.requestFocus() }
            }
            if (!bodyHasFocus) {
                runCatching { confirmFocusRequester.requestFocus() }
            }
        }
    }

    ApplyDynamicArtworkBlurDialogStyle()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(tokens.dialogCornerRadius),
        containerColor = colors.surfaceContainerHigh.copy(alpha = tokens.dialogContainerAlpha),
        tonalElevation = tokens.dialogTonalElevation,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        icon = icon,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Box(
                modifier = if (isTvPresentation) {
                    Modifier
                        .focusRequester(bodyFocusRequester)
                        .focusGroup()
                        .onFocusChanged { bodyHasFocus = it.hasFocus }
                } else {
                    Modifier
                },
            ) {
                text()
            }
        },
        confirmButton = {
            Box(
                modifier = if (isTvPresentation) {
                    Modifier
                        .focusRequester(confirmFocusRequester)
                        .focusGroup()
                } else {
                    Modifier
                },
            ) {
                confirmButton()
            }
        },
        dismissButton = dismissButton,
    )
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.tvFocusable(
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            addFocusTarget = false,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
