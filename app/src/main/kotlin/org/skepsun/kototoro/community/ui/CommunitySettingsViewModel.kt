package org.skepsun.kototoro.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.community.data.CommunityKeyStore
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject

/** Which community dialog the services page shows. */
sealed interface CommunityDialog {
    data object Onboarding : CommunityDialog
    data class Restore(val invalid: Boolean = false) : CommunityDialog
    data class RecoveryKey(val key: String) : CommunityDialog
}

/**
 * Turning the community on (plan §8): the first time, the reader sees what becomes public and chooses a new identity or
 * an existing recovery key; the feature is on only after that. Turning it off keeps the identity for next time.
 */
@HiltViewModel
class CommunitySettingsViewModel @Inject constructor(
    private val keyStore: CommunityKeyStore,
    private val settings: AppSettings,
) : ViewModel() {

    private val _dialog = MutableStateFlow<CommunityDialog?>(null)
    val dialog: StateFlow<CommunityDialog?> = _dialog

    fun setEnabled(enabled: Boolean) {
        when {
            !enabled -> settings.isCommunityEnabled = false
            // A key the Keystore can no longer open (device restored) needs the recovery key again.
            keyStore.hasIdentity -> io { if (keyStore.keys() != null) settings.isCommunityEnabled = true else _dialog.value = CommunityDialog.Restore() }
            else -> _dialog.value = CommunityDialog.Onboarding
        }
    }

    fun createIdentity() = io {
        keyStore.create()
        settings.isCommunityEnabled = true
        showRecoveryKey()
    }

    fun startRestore() {
        _dialog.value = CommunityDialog.Restore()
    }

    fun restore(recoveryKey: String) = io {
        if (keyStore.restore(recoveryKey) == null) {
            _dialog.value = CommunityDialog.Restore(invalid = true)
        } else {
            settings.isCommunityEnabled = true
            _dialog.value = null
        }
    }

    fun showRecoveryKey() = io {
        _dialog.value = keyStore.recoveryKey()?.let(CommunityDialog::RecoveryKey) ?: CommunityDialog.Restore()
    }

    fun dismiss() {
        _dialog.value = null
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
