package org.skepsun.kototoro.main.ui.protect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@Composable
internal fun ProtectScreen(
	password: String,
	errorMessage: String?,
	isLoading: Boolean,
	isNumericPassword: Boolean,
	canUseBiometric: Boolean,
	shouldFocusPassword: Boolean,
	onPasswordChange: (String) -> Unit,
	onUnlock: (String) -> Unit,
	onUseBiometric: () -> Unit,
	onCancel: () -> Unit,
) {
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	val passwordFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
	val canUnlock = password.isNotEmpty()

	LaunchedEffect(shouldFocusPassword) {
		if (shouldFocusPassword) {
			passwordFocusRequester.requestFocus()
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.windowInsetsPadding(WindowInsets.systemBars)
			.padding(dimensionResource(R.dimen.screen_padding)),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 8.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Image(
				painter = painterResource(R.drawable.ic_lock),
				contentDescription = null,
				modifier = Modifier.size(24.dp),
				colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
			)
			Text(
				text = stringResource(R.string.app_name),
				style = MaterialTheme.typography.headlineSmall,
				modifier = Modifier.padding(top = 16.dp),
			)
		}
		Text(
			text = stringResource(R.string.enter_password),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 12.dp),
			textAlign = TextAlign.Center,
		)
		OutlinedTextField(
			value = password,
			onValueChange = { onPasswordChange(it.take(24)) },
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 30.dp)
				.then(Modifier.focusRequester(passwordFocusRequester)),
			enabled = !isLoading,
			isError = errorMessage != null,
			textStyle = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Center),
			visualTransformation = if (passwordVisible) {
				VisualTransformation.None
			} else {
				PasswordVisualTransformation()
			},
			trailingIcon = {
				if (canUseBiometric && password.isEmpty()) {
					IconButton(onClick = onUseBiometric) {
						Icon(
							painter = painterResource(androidx.biometric.R.drawable.fingerprint_dialog_fp_icon),
							contentDescription = stringResource(androidx.biometric.R.string.use_biometric_label),
						)
					}
				} else {
					IconButton(onClick = { passwordVisible = !passwordVisible }) {
						Icon(
							imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
							contentDescription = null,
						)
					}
				}
			},
			keyboardOptions = KeyboardOptions(
				keyboardType = if (isNumericPassword) KeyboardType.NumberPassword else KeyboardType.Password,
				imeAction = ImeAction.Done,
			),
			keyboardActions = KeyboardActions(
				onDone = { if (canUnlock) onUnlock(password) },
			),
		)
		errorMessage?.let { message ->
			Text(
				text = message,
				color = MaterialTheme.colorScheme.error,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
			)
		}
		Spacer(modifier = Modifier.weight(1f))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			OutlinedButton(onClick = onCancel) {
				Text(text = stringResource(android.R.string.cancel))
			}
			Button(
				enabled = canUnlock,
				onClick = { onUnlock(password) },
			) {
				Text(text = stringResource(R.string.next))
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun ProtectScreenPreview() {
	KototoroTheme {
		ProtectScreen(
			password = "1234",
			errorMessage = null,
			isLoading = false,
			isNumericPassword = true,
			canUseBiometric = false,
			shouldFocusPassword = false,
			onPasswordChange = {},
			onUnlock = {},
			onUseBiometric = {},
			onCancel = {},
		)
	}
}
