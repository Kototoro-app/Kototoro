package org.skepsun.kototoro.backups.ui.periodical
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupFlowPolicy
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.ErrorReporterReceiver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.CoroutineIntentService
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.logBackupFlow
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class PeriodicalBackupService : CoroutineIntentService() {

    @Inject
    lateinit var externalBackupStorage: ExternalBackupStorage

    @Inject
    lateinit var telegramBackupUploader: TelegramBackupUploader

    @Inject
    lateinit var repository: BackupRepository

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var backupFlowPolicy: BackupFlowPolicy

    @Inject
    lateinit var backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator

    override suspend fun IntentJobContext.processIntent(intent: Intent) {
    logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "backup_start")
    val plan = backupFlowPolicy.periodicalBackupPlan(telegramBackupUploader.isAvailable)
    if (!plan.decision.allowed) {
        logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "backup_skipped", reason = plan.decision.reason)
        return
    }
    val hasLocalCopyDestination = plan.destinations.hasLocalCopyDestination
    val hasTelegramDestination = plan.destinations.hasTelegramDestination
    val hasWebDavDestination = plan.destinations.hasWebDavDestination

    // 频率判定：如果保留本地副本，则参考本地最近一次备份时间；
    // 如果不保留本地副本但启用了 WebDAV 上传，则参考最近一次 WebDAV 上传时间。
    val localLast = if (hasLocalCopyDestination) externalBackupStorage.getLastBackupDate()?.time else null
    val webDavLast = if (hasWebDavDestination) settings.backupWebDavLastUploadTime else 0L
    val frequencyDecision = backupFlowPolicy.periodicalBackupFrequencyDecision(
        now = System.currentTimeMillis(),
        localLastBackupTime = localLast,
        webDavLastUploadTime = webDavLast,
        destinations = plan.destinations,
    )
    if (!frequencyDecision.allowed) {
        logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "backup_skipped", reason = frequencyDecision.reason)
        return
    }

        val output = BackupUtils.createTempFile(applicationContext)
        try {
            ZipOutputStream(output.outputStream()).use {
                repository.createBackup(it, null)
            }
            // 仅在启用了“保留本地副本”且配置了目录时写入本地
            if (hasLocalCopyDestination) {
                runCatching {
                    externalBackupStorage.put(output)
                    externalBackupStorage.trim(settings.periodicalBackupMaxCount)
                }.onSuccess {
                    logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "local_copy_written")
                }.onFailure {
                    Log.e(TAG, "Failed to write local backup copy", it)
                }
            }
            if (settings.isBackupTelegramUploadEnabled && telegramBackupUploader.isAvailable) {
                telegramBackupUploader.uploadBackup(output)
                logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "telegram_upload_complete")
            }
            if (settings.isBackupWebDavUploadEnabled) {
                val uploadResult = backupWebDavUploadCoordinator.uploadAndCommit(
                    file = output,
                    uploadKind = "auto",
                )
                logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "webdav_upload_complete", reason = null, "nextVersion" to uploadResult.targetVersion)
            }
            logBackupFlow(TAG, flow = BackupFlow.PERIODICAL_BACKUP, event = "backup_complete")
        } finally {
            output.delete()
        }
    }

    override fun IntentJobContext.onError(error: Throwable) {
        if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
            return
        }
        BaseBackupRestoreService.createNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setSilent(true)
            .setAutoCancel(true)
        val title = getString(R.string.periodic_backups)
        val message = getString(
            R.string.inline_preference_pattern,
            getString(R.string.packup_creation_failed),
            error.getDisplayMessage(resources),
        )
        notification
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
                    .setSummaryText(getString(R.string.packup_creation_failed))
                    .setBigContentTitle(title),
            )
        ErrorReporterReceiver.getNotificationAction(applicationContext, error, startId, TAG)?.let { action ->
            notification.addAction(action)
        }
        notification.setContentIntent(
            PendingIntentCompat.getActivity(
                applicationContext,
                0,
                AppRouter.periodicBackupSettingsIntent(applicationContext),
                0,
                false,
            ),
        )
        NotificationManagerCompat.from(applicationContext).notify(TAG, startId, notification.build())
    }

    private companion object {

        const val CHANNEL_ID = BaseBackupRestoreService.CHANNEL_ID
        const val TAG = "periodical_backup"
    }
}
