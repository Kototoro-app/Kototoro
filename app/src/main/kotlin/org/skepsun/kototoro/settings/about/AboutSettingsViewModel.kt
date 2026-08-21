package org.skepsun.kototoro.settings.about

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.core.github.AppUpdateRepository
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import javax.inject.Inject

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
) : BaseViewModel() {

    val isUpdateSupported = flow {
        emit(appUpdateRepository.isUpdateSupported())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isUpdateAvailable = appUpdateRepository.observeAvailableUpdate()
        .map(::shouldShowUpdateBadge)
        .stateIn(viewModelScope, SharingStarted.Eagerly, appUpdateRepository.isUpdateAvailable)

    val onUpdateAvailable = MutableEventFlow<AppVersion?>()

    fun checkForUpdates() {
        launchLoadingJob {
            val update = appUpdateRepository.fetchUpdate()
            onUpdateAvailable.call(update)
        }
    }
}

internal fun shouldShowUpdateBadge(update: AppVersion?): Boolean = update != null
