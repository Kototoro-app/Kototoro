package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
		text = text,
		confirmButton = confirmButton,
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
		modifier = modifier,
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
		)
	}
}
