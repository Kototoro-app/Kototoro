package org.skepsun.kototoro.backups.ui

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ShareCompat
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupPayloadGuard
import org.skepsun.kototoro.core.ErrorReporterReceiver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.CoroutineIntentService
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getFileDisplayName
import androidx.appcompat.R as appcompatR

abstract class BaseBackupRestoreService : CoroutineIntentService() {

	protected abstract val notificationTag: String
	protected abstract val isRestoreService: Boolean

	protected lateinit var notificationManager: NotificationManagerCompat
		private set

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(applicationContext)
		createNotificationChannel(this)
	}

	override fun IntentJobContext.onError(error: Throwable) {
		showResultNotification(null, CompositeResult.failure(error))
	}

	protected fun IntentJobContext.showResultNotification(
		fileUri: Uri?,
		result: CompositeResult,
		showLegacyJarReposImportedHint: Boolean = false,
		showWorkMigrationNormalizationHint: Boolean = false,
	) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setAutoCancel(true)
			.setSubText(fileUri?.let { contentResolver.getFileDisplayName(it) })
		when {
			result.isAllSuccess -> {
				if (isRestoreService) {
					notification
						.setContentTitle(getString(R.string.restoring_backup))
						.setContentText(
							if (showWorkMigrationNormalizationHint && showLegacyJarReposImportedHint) {
								getString(R.string.data_restored_success_legacy_requires_normalization_with_jar_hint)
							} else if (showWorkMigrationNormalizationHint) {
								getString(R.string.data_restored_success_legacy_requires_normalization)
							} else if (showLegacyJarReposImportedHint) {
								getString(R.string.data_restored_success_legacy_jar_hint)
							} else {
								getString(R.string.data_restored_success)
							},
						)
				} else {
					notification
						.setContentTitle(getString(R.string.backup_saved))
						.setContentText(fileUri?.let { contentResolver.getFileDisplayName(it) })
						.setSubText(null)

				}
				notification.setSmallIcon(R.drawable.ic_stat_done)
			}

			result.isAllFailed || !isRestoreService -> {
				val title = getString(if (isRestoreService) R.string.data_not_restored else R.string.error_occurred)
				val message = result.failures.joinToString("\n") {
					it.getDisplayMessage(applicationContext.resources)
				}
				notification
					.setContentText(if (isRestoreService) getString(R.string.data_not_restored_text) else message)
					.setBigText(title, message)
					.setSmallIcon(android.R.drawable.stat_notify_error)
				result.failures.firstNotNullOfOrNull { error ->
					ErrorReporterReceiver.getNotificationAction(applicationContext, error, startId, notificationTag)
				}?.let { action ->
					notification.addAction(action)
				}
			}

			else -> {
				notification
					.setContentTitle(getString(R.string.restoring_backup))
					.setContentText(
						if (showWorkMigrationNormalizationHint && showLegacyJarReposImportedHint) {
							getString(R.string.data_restored_with_errors_legacy_requires_normalization_with_jar_hint)
						} else if (showWorkMigrationNormalizationHint) {
							getString(R.string.data_restored_with_errors_legacy_requires_normalization)
						} else if (showLegacyJarReposImportedHint) {
							getString(R.string.data_restored_with_errors_legacy_jar_hint)
						} else {
							getString(R.string.data_restored_with_errors)
						},
					)
					.setSmallIcon(R.drawable.ic_stat_done)
			}
		}
		notification.setContentIntent(
			PendingIntentCompat.getActivity(
				applicationContext,
				0,
				when (result.failures.firstOrNull()) {
					is BackupPayloadGuard.MissingProjectionAnchorsException,
					is BackupPayloadGuard.WorkEntityMissingSyncIdException,
						-> AppRouter.entityOrganizeSettingsIntent(applicationContext)

					else -> AppRouter.homeIntent(this@BaseBackupRestoreService)
				},
				0,
				false,
			),
		)
		if (!isRestoreService && fileUri != null) {
			val shareIntent = ShareCompat.IntentBuilder(this@BaseBackupRestoreService)
				.setStream(fileUri)
				.setType("application/zip")
				.setChooserTitle(R.string.share_backup)
				.createChooserIntent()
			notification.addAction(
				appcompatR.drawable.abc_ic_menu_share_mtrl_alpha,
				getString(R.string.share),
				PendingIntentCompat.getActivity(this@BaseBackupRestoreService, 0, shareIntent, 0, false),
			)
		}
		notificationManager.notify(notificationTag, startId, notification.build())
	}

	protected fun NotificationCompat.Builder.setBigText(title: String, text: CharSequence) = setStyle(
		NotificationCompat.BigTextStyle()
			.bigText(text)
			.setSummaryText(text)
			.setBigContentTitle(title),
	)

	companion object {

		const val CHANNEL_ID = "backup_restore"

		fun createNotificationChannel(context: Context) {
			val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
				.setName(context.getString(R.string.backup_restore))
				.setShowBadge(true)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
			NotificationManagerCompat.from(context).createNotificationChannel(channel)
		}
	}
}
