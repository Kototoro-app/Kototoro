package org.skepsun.kototoro.scrobbling.mangabaka.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.ui.config.ScrobblerConfigActivity
import org.skepsun.kototoro.scrobbling.mangabaka.data.MangaBakaRepository
import org.skepsun.kototoro.scrobbling.mangabaka.domain.MangaBakaScrobbler
import javax.inject.Inject

@AndroidEntryPoint
class MangaBakaAuthActivity : BaseComposeActivity() {
    private val viewModel: MangaBakaAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processCallback(intent)
        setComposeContent {
            val loading by viewModel.loading.collectAsStateWithLifecycle()
            val succeeded by viewModel.succeeded.collectAsStateWithLifecycle()
            val failed by viewModel.failed.collectAsStateWithLifecycle()
            val uriHandler = LocalUriHandler.current
            // Credentials are neither saved in instance state nor placed in implicit intents.
            var token by remember { mutableStateOf("") }
            LaunchedEffect(succeeded) {
                if (succeeded) {
                    startActivity(Intent(this@MangaBakaAuthActivity, ScrobblerConfigActivity::class.java)
                        .putExtra(AppRouter.KEY_ID, ScrobblerService.MANGABAKA.id))
                    finish()
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.mangabaka), style = MaterialTheme.typography.headlineSmall)
                if (viewModel.hasOAuth) {
                    Button(enabled = !loading, onClick = {
                        uriHandler.openUri(viewModel.authorizationUrl())
                    }) { Text(stringResource(R.string.mangabaka_browser_login)) }
                }
                Text(stringResource(R.string.mangabaka_token_hint))
                TvTextButton(enabled = !loading, onClick = {
                    uriHandler.openUri("https://mangabaka.org/my/settings/api-and-apps")
                }) { Text(stringResource(R.string.mangabaka_create_token)) }
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(stringResource(R.string.mangabaka_access_token)) },
                    enabled = !loading, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().tvTextInputFocusEscape(),
                )
                if (failed) {
                    Text(stringResource(R.string.mangabaka_auth_failed), color = MaterialTheme.colorScheme.error)
                }
                Button(enabled = !loading && token.trim().startsWith("mb-") && token.trim().length > 3, onClick = {
                    viewModel.login(token.trim())
                    token = ""
                }) { Text(stringResource(R.string._continue)) }
                if (loading) CircularProgressIndicator()
                TvTextButton(onClick = ::finish) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processCallback(intent)
    }

    private fun processCallback(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "kototoro" || uri.host != "mangabaka-auth") return
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (!code.isNullOrBlank() && !state.isNullOrBlank()) viewModel.login("$code\n$state")
        else viewModel.failed.value = true
        // Do not replay an authorization code after Activity recreation.
        intent.data = null
    }
}

@HiltViewModel
class MangaBakaAuthViewModel @Inject constructor(
    private val repository: MangaBakaRepository,
    private val scrobbler: MangaBakaScrobbler,
) : ViewModel() {
    val loading = MutableStateFlow(false)
    val succeeded = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val hasOAuth: Boolean get() = repository.auth.clientId.isNotBlank()

    fun authorizationUrl(): String = repository.oauthUrl

    fun login(credential: String) {
        if (loading.value || succeeded.value) return
        loading.value = true
        failed.value = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                scrobbler.authorize(credential)
                succeeded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed.value = true
            } finally {
                loading.value = false
            }
        }
    }
}
