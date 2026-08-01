package org.skepsun.kototoro.backups.ui.backup

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.widget.Toast
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupPayloadGuard
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.withPartialWakeLock
import org.skepsun.kototoro.core.util.progress.Progress
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class BackupService : BaseBackupRestoreService() {

	override val notificationTag = TAG
	override val isRestoreService = false

	@Inject
	lateinit var repository: BackupRepository

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		val format = intent.getStringExtra(EXTRA_EXPORT_FORMAT)
			?.let { runCatching { BackupRepository.ExportFormat.valueOf(it) }.getOrNull() }
			?: BackupRepository.ExportFormat.KOTOTORO
		val notification = buildNotification(Progress.INDETERMINATE, format)
		setForeground(
			FOREGROUND_NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		val destination = intent.getStringExtra(AppRouter.KEY_DATA)?.toUriOrNull() ?: throw FileNotFoundException()
		powerManager.withPartialWakeLock(TAG) {
			val progress = MutableStateFlow(Progress.INDETERMINATE)
			val progressUpdateJob = if (checkNotificationPermission(CHANNEL_ID)) {
				launch {
					progress.collect {
						notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(it, format))
					}
				}
			} else {
				null
			}
			val tempFile = File.createTempFile("manual_backup_", ".bk.zip", cacheDir)
			try {
				ZipOutputStream(tempFile.outputStream()).use { output ->
					repository.createBackup(output, progress, format)
				}
				if (format == BackupRepository.ExportFormat.KOTOTORO) {
					BackupPayloadGuard.requireRestorableWorkSnapshot(
						file = tempFile,
						operation = "manual backup creation",
					)
				}
				val expectedBytes = tempFile.length()
				FileInputStream(tempFile).use { input ->
					contentResolver.openOutputStream(destination, "wt")?.use { output ->
						val copiedBytes = input.copyTo(output)
						output.flush()
						if (copiedBytes != expectedBytes) {
							throw IOException("Backup write was incomplete: copied $copiedBytes of $expectedBytes bytes.")
						}
					} ?: throw FileNotFoundException()
				}
			} catch (e: Throwable) {
				try {
					DocumentFile.fromSingleUri(applicationContext, destination)?.delete()
				} catch (e2: Throwable) {
					e.addSuppressed(e2)
				}
				withContext(Dispatchers.Main) {
					Toast.makeText(
						this@BackupService,
						R.string.backup_failed_check_notification,
						Toast.LENGTH_SHORT,
					).show()
				}
				throw e
			} finally {
				tempFile.delete()
			}
			progressUpdateJob?.cancelAndJoin()
			contentResolver.notifyChange(destination, null)
			showResultNotification(destination, CompositeResult.success())
			withContext(Dispatchers.Main) {
				val message = if (format == BackupRepository.ExportFormat.KOTATSU) {
					R.string.export_kotatsu_backup_saved
				} else {
					R.string.backup_saved
				}
				Toast.makeText(this@BackupService, message, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun IntentJobContext.buildNotification(
		progress: Progress,
		format: BackupRepository.ExportFormat,
	): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(
				getString(
					if (format == BackupRepository.ExportFormat.KOTATSU) {
						R.string.export_kotatsu_backup
					} else {
						R.string.creating_backup
					},
				),
			)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(
				progress.total.coerceAtLeast(0),
				progress.progress.coerceAtLeast(0),
				progress.isIndeterminate,
			)
			.setContentText(
				if (progress.isIndeterminate) {
					getString(R.string.processing_)
				} else {
					getString(R.string.fraction_pattern, progress.progress, progress.total)
				},
			)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				getCancelIntent(),
			).build()
	}

	companion object {

		private const val TAG = "BACKUP"
		private const val FOREGROUND_NOTIFICATION_ID = 33
		private const val EXTRA_EXPORT_FORMAT = "export_format"

		@CheckResult
		fun start(context: Context, uri: Uri): Boolean = try {
			val intent = Intent(context, BackupService::class.java)
			intent.putExtra(AppRouter.KEY_DATA, uri.toString())
			intent.putExtra(EXTRA_EXPORT_FORMAT, BackupRepository.ExportFormat.KOTOTORO.name)
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}

		@CheckResult
		fun startKotatsuExport(context: Context, uri: Uri): Boolean = try {
			val intent = Intent(context, BackupService::class.java)
			intent.putExtra(AppRouter.KEY_DATA, uri.toString())
			intent.putExtra(EXTRA_EXPORT_FORMAT, BackupRepository.ExportFormat.KOTATSU.name)
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}
	}
}
