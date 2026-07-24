package org.skepsun.kototoro.settings.protect

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.observeEvent

@AndroidEntryPoint
class ProtectSetupActivity : BaseComposeActivity() {

	private val viewModel by viewModels<ProtectSetupViewModel>()
	private var password by mutableStateOf("")
	private var passwordError by mutableStateOf<String?>(null)
	private var biometricEnabled by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		biometricEnabled = viewModel.isBiometricEnabled

		viewModel.onPasswordSet.observeEvent(this) {
			finishAfterTransition()
		}
		viewModel.onPasswordMismatch.observeEvent(this) {
			passwordError = getString(R.string.passwords_mismatch)
		}
		viewModel.onClearText.observeEvent(this) {
			password = ""
			passwordError = null
		}

		setComposeContent {
			ProtectSetupScreen(
				password = password,
				passwordError = passwordError,
				isSecondStep = viewModel.isSecondStep.collectAsStateWithLifecycle().value,
				isBiometricAvailable = isBiometricAvailable(),
				isBiometricEnabled = biometricEnabled,
				onPasswordChange = {
					password = it.take(24)
					passwordError = null
				},
				onNext = viewModel::onNextClick,
				onBiometricEnabledChange = {
					biometricEnabled = it
					viewModel.setBiometricEnabled(it)
				},
				onCancel = ::finish,
			)
		}
	}

	private fun isBiometricAvailable(): Boolean {
		return packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
	}
}
