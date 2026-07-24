package org.skepsun.kototoro.sync.ui

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.isHttpUrl
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.sync.data.SyncSettings
import org.skepsun.kototoro.sync.domain.SyncAuthResult

private const val PAGE_EMAIL = 0
private const val PAGE_PASSWORD = 1
private const val PASSWORD_MIN_LENGTH = 4

@AndroidEntryPoint
class SyncAuthActivity : BaseComposeActivity() {
    private var accountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private var resultBundle: Bundle? = null
    private var errorMessage by mutableStateOf<String?>(null)
    private val viewModel by viewModels<SyncAuthViewModel>()
    private val regexEmail = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountAuthenticatorResponse = intent.getParcelableExtraCompat(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        accountAuthenticatorResponse?.onRequestContinued()
        viewModel.onTokenObtained.observeEvent(this, ::onTokenReceived)
        viewModel.onError.observeEvent(this) { errorMessage = it.getDisplayMessage(resources) }
        viewModel.onAccountAlreadyExists.observeEvent(this) { onAccountAlreadyExists() }
        setComposeContent {
            var page by rememberSaveable { mutableStateOf(PAGE_EMAIL) }
            var email by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            var showHostDialog by rememberSaveable { mutableStateOf(false) }
            var hostValue by rememberSaveable { mutableStateOf(viewModel.syncURL.value) }
            val loading by viewModel.isLoading.collectAsStateWithLifecycle()
            BackHandler(enabled = page == PAGE_PASSWORD && !loading) { page = PAGE_EMAIL }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Text(getString(R.string.sync_title), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                Text(getString(R.string.sync_auth_hint))
                if (page == PAGE_EMAIL) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(getString(R.string.email)) },
                        enabled = !loading,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showHostDialog = true }) { Text(getString(R.string.settings)) }
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(24) },
                        label = { Text(getString(R.string.password)) },
                        enabled = !loading,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (loading) CircularProgressIndicator()
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        if (page == PAGE_EMAIL) {
                            setResult(RESULT_CANCELED)
                            finish()
                        } else page = PAGE_EMAIL
                    }) { Text(getString(if (page == PAGE_EMAIL) android.R.string.cancel else R.string.back)) }
                    TextButton(
                        enabled = !loading && if (page == PAGE_EMAIL) regexEmail.matches(email.trim()) else password.length >= PASSWORD_MIN_LENGTH,
                        onClick = {
                            if (page == PAGE_EMAIL) page = PAGE_PASSWORD
                            else viewModel.obtainToken(email.trim(), password)
                        },
                    ) { Text(getString(if (page == PAGE_EMAIL) R.string.next else R.string.done)) }
                }
            }
            if (showHostDialog) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(getString(R.string.server_address)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(getString(R.string.sync_host_description))
                            OutlinedTextField(value = hostValue, onValueChange = { hostValue = it }, singleLine = true)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val normalized = if (hostValue.isHttpUrl()) hostValue else "http://$hostValue"
                            viewModel.syncURL.value = normalized
                            hostValue = normalized
                            showHostDialog = false
                        }) { Text(getString(android.R.string.ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showHostDialog = false }) { Text(getString(android.R.string.cancel)) }
                    },
                )
            }
            errorMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text(getString(R.string.error)) },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = { errorMessage = null }) { Text(getString(R.string.close)) } },
                )
            }
        }
    }

    override fun finish() {
        accountAuthenticatorResponse?.let { response ->
            resultBundle?.also(response::onResult)
                ?: response.onError(AccountManager.ERROR_CODE_CANCELED, getString(R.string.canceled))
        }
        super.finish()
    }

    private fun onTokenReceived(authResult: SyncAuthResult) {
        val manager = AccountManager.get(this)
        val account = Account(authResult.email, getString(R.string.account_type_sync))
        val userdata = Bundle(1).apply { putString(SyncSettings.KEY_SYNC_URL, authResult.syncURL) }
        resultBundle = Bundle().apply {
            if (manager.addAccountExplicitly(account, authResult.password, userdata)) {
                putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                putString(AccountManager.KEY_AUTHTOKEN, authResult.token)
                manager.setAuthToken(account, account.type, authResult.token)
            } else putString(AccountManager.KEY_ERROR_MESSAGE, getString(R.string.account_already_exists))
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun onAccountAlreadyExists() {
        accountAuthenticatorResponse?.onError(
            AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
            getString(R.string.account_already_exists),
        )
        super.finishAfterTransition()
    }
}
