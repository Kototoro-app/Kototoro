package org.skepsun.kototoro.backups.domain

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupService
import org.skepsun.kototoro.backups.ui.webdav.WebDavAutoRestoreWorker
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.logBackupFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupStartupCoordinator @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val backupFlowPolicy: BackupFlowPolicy,
) {

	fun startOnFirstLaunch(scope: CoroutineScope) {
		startPeriodicalBackupService()
		scheduleAutoRestore(scope)
	}

	private fun startPeriodicalBackupService() {
		logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "startup_requested")
		runCatching {
			appContext.startService(Intent(appContext, PeriodicalBackupService::class.java))
		}.onFailure {
			logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "startup_failed", reason = it::class.java.simpleName)
			it.printStackTraceDebug()
		}
	}

	private fun scheduleAutoRestore(scope: CoroutineScope) {
		val decision = backupFlowPolicy.autoRestoreStartupDecision()
		if (!decision.allowed) {
			logBackupFlow(
				TAG,
				flow = BackupFlow.WEBDAV_AUTO_RESTORE,
				event = "startup_skipped",
				reason = decision.reason,
			)
			return
		}
		logBackupFlow(
			TAG,
			flow = BackupFlow.WEBDAV_AUTO_RESTORE,
			event = "startup_scheduled",
			reason = null,
			"delayMs" to AUTO_RESTORE_START_DELAY_MS,
		)
		scope.launch {
			runCatching {
				WebDavAutoRestoreWorker.enqueue(appContext, delayMs = AUTO_RESTORE_START_DELAY_MS)
				logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "startup_requested")
			}.onFailure {
				logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "startup_failed", reason = it::class.java.simpleName)
				it.printStackTraceDebug()
			}
		}
	}

	private companion object {
		private const val TAG = "BackupStartupCoordinator"
		private const val AUTO_RESTORE_START_DELAY_MS = 3000L
	}
}
