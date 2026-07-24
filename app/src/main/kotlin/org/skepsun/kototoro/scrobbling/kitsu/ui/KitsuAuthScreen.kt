package org.skepsun.kototoro.scrobbling.kitsu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

private val emailRegex = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", RegexOption.IGNORE_CASE)

@Composable
fun KitsuAuthScreen(
    onCancel: () -> Unit,
    onContinue: (email: String, password: String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val passwordFocusRequester = remember { FocusRequester() }
    val canContinue = emailRegex.matches(email.trim()) && password.trim().length >= 3
    val submit = {
        if (canContinue) onContinue(email.trim(), password.trim())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        AuthHeader(
            title = R.string.kitsu,
            icon = R.drawable.ic_kitsu,
            tintIcon = true,
        )
        Text(
            text = stringResource(R.string.email_password_enter_hint),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(512) },
            placeholder = { Text(stringResource(R.string.email)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
            modifier = authFieldModifier().padding(top = 30.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(512) },
            placeholder = { Text(stringResource(R.string.password)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (passwordVisible) R.string.hide else R.string.show),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { submit() },
            ),
            modifier = authFieldModifier().padding(top = 8.dp).focusRequester(passwordFocusRequester),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            FilledTonalButton(
                enabled = canContinue,
                onClick = submit,
            ) { Text(stringResource(R.string._continue)) }
        }
    }
}

@Composable
private fun AuthHeader(title: Int, icon: Int, tintIcon: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = if (tintIcon) ColorFilter.tint(MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun authFieldModifier() = Modifier
    .fillMaxWidth()
    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    .padding(horizontal = 16.dp)

@Preview(showBackground = true)
@Composable
private fun KitsuAuthScreenPreview() {
    KototoroTheme { KitsuAuthScreen({}, { _, _ -> }) }
}
