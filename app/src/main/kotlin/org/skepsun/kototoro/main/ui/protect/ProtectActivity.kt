package org.skepsun.kototoro.main.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.observeEvent

@AndroidEntryPoint
class ProtectActivity :
	BaseComposeActivity(),
	AuthenticationResultCallback {

	private val viewModel by viewModels<ProtectViewModel>()
	private var canUseBiometric by mutableStateOf(false)
	private var shouldFocusPassword by mutableStateOf(false)
	private var password by mutableStateOf("")
	private var errorMessage by mutableStateOf<String?>(null)

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

		viewModel.onError.observeEvent(this) { error ->
			errorMessage = error.getDisplayMessage(resources)
		}
		viewModel.onUnlockSuccess.observeEvent(this) {
			val sourceIntent = intent.getParcelableExtraCompat<Intent>(EXTRA_INTENT)
			startActivity(sourceIntent)
			finishAfterTransition()
		}

		setComposeContent {
			ProtectScreen(
				password = password,
				errorMessage = errorMessage,
				isLoading = viewModel.isLoading.value,
				isNumericPassword = viewModel.isNumericPassword,
				canUseBiometric = canUseBiometric,
				shouldFocusPassword = shouldFocusPassword,
				onPasswordChange = {
					password = it
					errorMessage = null
				},
				onUnlock = viewModel::tryUnlock,
				onUseBiometric = ::useFingerprint,
				onCancel = ::finish,
			)
		}

		lifecycleScope.launch {
			withResumed {
				canUseBiometric = useFingerprint()
				shouldFocusPassword = !canUseBiometric
			}
		}
	}

	override fun onAuthResult(result: AuthenticationResult) {
		if (result.isSuccess()) {
			viewModel.unlock()
		}
	}

	private fun useFingerprint(): Boolean {
		if (!viewModel.isBiometricEnabled) {
			return false
		}
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BIOMETRIC_SUCCESS) {
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			title = getString(R.string.app_name),
			authFallback = Biometric.Fallback.NegativeButton(getString(android.R.string.cancel)),
			init = {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			},
		)
		biometricPrompt.launch(request)
		return true
	}

	companion object {

		private const val EXTRA_INTENT = "src_intent"

		fun newIntent(context: Context, sourceIntent: Intent): Intent {
			return Intent(context, ProtectActivity::class.java)
				.putExtra(EXTRA_INTENT, sourceIntent)
		}
	}
}
