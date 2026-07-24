package org.skepsun.kototoro.settings.protect

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

private const val MIN_PASSWORD_LENGTH = 4

@Composable
internal fun ProtectSetupScreen(
	password: String,
	passwordError: String?,
	isSecondStep: Boolean,
	isBiometricAvailable: Boolean,
	isBiometricEnabled: Boolean,
	onPasswordChange: (String) -> Unit,
	onNext: (String) -> Unit,
	onBiometricEnabledChange: (Boolean) -> Unit,
	onCancel: () -> Unit,
) {
	val passwordFocusRequester = remember { FocusRequester() }
	val canContinue = password.length >= MIN_PASSWORD_LENGTH
	val helperText = if (isSecondStep) {
		stringResource(R.string.repeat_password)
	} else if (!canContinue) {
		stringResource(R.string.password_length_hint)
	} else {
		null
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
			androidx.compose.foundation.Image(
				painter = androidx.compose.ui.res.painterResource(R.drawable.ic_lock),
				contentDescription = null,
				modifier = Modifier.size(24.dp),
				colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
			)
			Text(
				text = stringResource(R.string.protect_application),
				style = MaterialTheme.typography.headlineSmall,
				modifier = Modifier.padding(top = 16.dp),
			)
		}
		Text(
			text = stringResource(R.string.protect_application_subtitle),
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
				.focusRequester(passwordFocusRequester),
			isError = passwordError != null,
			textStyle = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Center),
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(
				keyboardType = KeyboardType.Password,
				imeAction = ImeAction.Done,
			),
			keyboardActions = KeyboardActions(
				onDone = { if (canContinue) onNext(password) },
			),
			supportingText = if (passwordError != null || helperText != null) {
				{
					Column {
						passwordError?.let {
							Text(text = it, color = MaterialTheme.colorScheme.error)
						}
						if (passwordError == null) {
							helperText?.let { Text(text = it) }
						}
					}
				}
			} else {
				null
			},
		)
		if (isSecondStep && isBiometricAvailable) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(text = stringResource(R.string.use_fingerprint))
				Switch(
					checked = isBiometricEnabled,
					onCheckedChange = onBiometricEnabledChange,
				)
			}
		}
		Spacer(modifier = Modifier.weight(1f))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (!isSecondStep) {
				OutlinedButton(onClick = onCancel) {
					Text(text = stringResource(android.R.string.cancel))
				}
			}
			Button(
				enabled = canContinue,
				onClick = { onNext(password) },
				modifier = if (isSecondStep) Modifier.align(Alignment.CenterVertically) else Modifier,
			) {
				Text(text = stringResource(if (isSecondStep) R.string.confirm else R.string.next))
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun ProtectSetupScreenPreview() {
	KototoroTheme {
		ProtectSetupScreen(
			password = "1234",
			passwordError = null,
			isSecondStep = false,
			isBiometricAvailable = true,
			isBiometricEnabled = true,
			onPasswordChange = {},
			onNext = {},
			onBiometricEnabledChange = {},
			onCancel = {},
		)
	}
}
