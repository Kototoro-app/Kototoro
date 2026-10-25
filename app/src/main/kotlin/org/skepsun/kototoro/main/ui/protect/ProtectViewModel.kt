package org.skepsun.kototoro.main.ui.protect

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.exceptions.WrongPasswordException
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.prefs.AppPasswordHash
import javax.inject.Inject

private const val PASSWORD_COMPARE_DELAY = 1_000L

@HiltViewModel
class ProtectViewModel @Inject constructor(
    private val settings: AppSettings,
    private val protectHelper: AppProtectHelper,
) : BaseViewModel() {

    private var job: Job? = null

    val onUnlockSuccess = MutableEventFlow<Unit>()

    val isBiometricEnabled
        get() = settings.isBiometricProtectionEnabled

    val isNumericPassword
        get() = settings.isAppPasswordNumeric

    fun tryUnlock(password: String) {
        if (job?.isActive == true) {
            return
        }
        job = launchLoadingJob {
            val stored = settings.appPassword
            if (AppPasswordHash.verify(password, stored)) {
                // A password stored by an earlier version as plain MD5 is upgraded on the first successful unlock.
                if (AppPasswordHash.needsUpgrade(stored)) settings.appPassword = AppPasswordHash.hash(password)
                unlock()
            } else {
                delay(PASSWORD_COMPARE_DELAY)
                throw WrongPasswordException()
            }
        }
    }

    fun unlock() {
        protectHelper.unlock()
        onUnlockSuccess.call(Unit)
    }
}
