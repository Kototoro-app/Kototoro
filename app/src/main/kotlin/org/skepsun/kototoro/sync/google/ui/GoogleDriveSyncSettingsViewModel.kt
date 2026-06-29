package org.skepsun.kototoro.sync.google.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncAuth
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncAuthorizationException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncRepository
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncResult
import org.skepsun.kototoro.sync.google.work.GoogleDriveSyncWorker
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject

data class GoogleDriveSyncUiState(
	val isEnabled: Boolean = true,
	val isSignedIn: Boolean = false,
	val accountEmail: String? = null,
	val accountName: String? = null,
	val intervalMinutes: Int = GoogleDriveSyncSettings.DEFAULT_INTERVAL_MINUTES,
	val isWifiOnly: Boolean = false,
	val isSyncOnStart: Boolean = true,
	val lastSyncTimestamp: Long = 0L,
	val lastError: String? = null,
	val isDirty: Boolean = false,
	val isSyncing: Boolean = false,
)

@HiltViewModel
class GoogleDriveSyncSettingsViewModel @Inject constructor(
	private val auth: GoogleDriveSyncAuth,
	private val settings: GoogleDriveSyncSettings,
	private val appSettings: AppSettings,
	private val repository: GoogleDriveSyncRepository,
	private val scheduler: GoogleDriveSyncWorker.Scheduler,
) : ViewModel() {

	private val _uiState = MutableStateFlow(readState())
	val uiState: StateFlow<GoogleDriveSyncUiState> = _uiState.asStateFlow()
	private val _authorizationRequests = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
	val authorizationRequests: SharedFlow<PendingIntent> = _authorizationRequests.asSharedFlow()

	init {
		viewModelScope.launch {
			repository.isSyncing.collect { syncing ->
				_uiState.value = readState().copy(isSyncing = syncing)
			}
		}
		viewModelScope.launch {
			settings.changes(
				GoogleDriveSyncSettings.KEY_SYNC_ENABLED,
				GoogleDriveSyncSettings.KEY_INTERVAL_MINUTES,
				GoogleDriveSyncSettings.KEY_WIFI_ONLY,
				GoogleDriveSyncSettings.KEY_SYNC_ON_START,
			).collect {
				refresh()
			}
		}
	}

	fun syncNow() {
		if (!settings.isSyncEnabled) {
			refresh()
			return
		}
		if (!settings.isSignedIn) {
			requestSignIn()
			return
		}
		viewModelScope.launch(Dispatchers.Default) {
			handleSyncResult(repository.sync())
			refresh()
		}
	}

	fun deleteRemoteData() {
		if (!settings.isSyncEnabled) {
			refresh()
			return
		}
		if (!settings.isSignedIn) {
			requestSignIn()
			return
		}
		viewModelScope.launch(Dispatchers.Default) {
			handleSyncResult(repository.deleteRemoteData())
			refresh()
		}
	}

	fun importLegacyRemoteData() {
		if (!settings.isSyncEnabled) {
			refresh()
			return
		}
		if (!settings.isSignedIn) {
			requestSignIn()
			return
		}
		viewModelScope.launch(Dispatchers.Default) {
			handleSyncResult(repository.importLegacyRemoteData())
			refresh()
		}
	}

	fun requestSignIn() {
		viewModelScope.launch(Dispatchers.Default) {
			try {
				val authorization = auth.authorize(promptSelectAccount = true)
				repository.onSignedIn(authorization.email, authorization.displayName, authorization.account)
				setWebDavEnabled(false)
				settings.isSyncEnabled = true
				scheduler.schedule()
			} catch (e: GoogleDriveSyncAuthorizationException) {
				val pendingIntent = e.authorizationIntent
				if (pendingIntent != null) {
					_authorizationRequests.tryEmit(pendingIntent)
				} else {
					settings.lastSyncError = e.message
				}
			} catch (e: Exception) {
				settings.lastSyncError = e.message ?: e.javaClass.simpleName
			}
			refresh()
		}
	}

	fun onAuthorizationResult(data: Intent?) {
		viewModelScope.launch(Dispatchers.Default) {
			try {
				val authorization = auth.authorizationFromIntent(data)
				repository.onSignedIn(authorization.email, authorization.displayName, authorization.account)
				setWebDavEnabled(false)
				settings.isSyncEnabled = true
				scheduler.schedule()
			} catch (e: Exception) {
				settings.lastSyncError = e.message ?: e.javaClass.simpleName
			}
			refresh()
		}
	}

	fun signOut() {
		viewModelScope.launch(Dispatchers.Default) {
			repository.signOut()
			scheduler.unschedule()
			refresh()
		}
	}

	fun setIntervalMinutes(value: Int) {
		settings.intervalMinutes = value
		viewModelScope.launch(Dispatchers.Default) {
			scheduler.schedule()
			refresh()
		}
	}

	fun setWifiOnly(value: Boolean) {
		settings.isWifiOnly = value
		viewModelScope.launch(Dispatchers.Default) {
			scheduler.schedule()
			refresh()
		}
	}

	fun setSyncOnStart(value: Boolean) {
		settings.isSyncOnStart = value
		refresh()
	}

	fun setEnabled(value: Boolean) {
		settings.isSyncEnabled = value
		viewModelScope.launch(Dispatchers.Default) {
			if (value) {
				setWebDavEnabled(false)
				scheduler.schedule()
			} else {
				scheduler.unschedule()
			}
			refresh()
		}
	}

	private fun refresh() {
		_uiState.value = readState()
	}

	private fun handleSyncResult(result: GoogleDriveSyncResult) {
		when (result) {
			is GoogleDriveSyncResult.Success -> Unit
			is GoogleDriveSyncResult.AuthorizationRequired -> {
				val pendingIntent = result.error.authorizationIntent
				if (pendingIntent != null) {
					_authorizationRequests.tryEmit(pendingIntent)
				}
			}
			is GoogleDriveSyncResult.Error -> settings.lastSyncError = result.message
			is GoogleDriveSyncResult.Disabled -> Unit
		}
	}

	private fun setWebDavEnabled(value: Boolean) {
		appSettings.isBackupWebDavUploadEnabled = value
		if (!value) {
			appSettings.isBackupWebDavAutoSyncEnabled = false
			appSettings.isBackupWebDavAutoRestoreEnabled = false
		}
	}

	private fun readState() = GoogleDriveSyncUiState(
		isEnabled = settings.isSyncEnabled,
		isSignedIn = settings.isSignedIn,
		accountEmail = settings.accountEmail,
		accountName = settings.accountName,
		intervalMinutes = settings.intervalMinutes,
		isWifiOnly = settings.isWifiOnly,
		isSyncOnStart = settings.isSyncOnStart,
		lastSyncTimestamp = settings.lastSyncTimestamp,
		lastError = settings.lastSyncError,
		isDirty = settings.isDirty,
		isSyncing = repository.isSyncing.value,
	)
}
