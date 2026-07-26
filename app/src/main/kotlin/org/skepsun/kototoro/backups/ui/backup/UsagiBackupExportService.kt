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
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.external.UsagiBackupExportRepository
import org.skepsun.kototoro.backups.external.UsagiBackupExportSummary
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getFileDisplayName
import org.skepsun.kototoro.core.util.ext.powerManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.withPartialWakeLock
import org.skepsun.kototoro.core.util.progress.Progress
import java.io.FileNotFoundException
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class UsagiBackupExportService : BaseBackupRestoreService() {

    override val notificationTag = TAG
    override val isRestoreService = false

    @Inject
    lateinit var repository: UsagiBackupExportRepository

    override suspend fun IntentJobContext.processIntent(intent: Intent) {
        val notification = buildNotification(Progress.INDETERMINATE)
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
                        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(it))
                    }
                }
            } else {
                null
            }
            val summary = try {
                contentResolver.openOutputStream(destination).use { output ->
                    checkNotNull(output) { "Unable to open export destination" }
                    repository.export(output, progress)
                }
            } catch (e: Throwable) {
                try {
                    DocumentFile.fromSingleUri(applicationContext, destination)?.delete()
                } catch (deleteError: Throwable) {
                    e.addSuppressed(deleteError)
                }
                throw e
            } finally {
                progressUpdateJob?.cancelAndJoin()
            }
            contentResolver.notifyChange(destination, null)
            withContext(Dispatchers.Main) {
                showExportToast(summary)
            }
            showExportResultNotification(destination, summary)
        }
    }

    private fun IntentJobContext.buildNotification(progress: Progress): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.export_usagi_backup))
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
            )
            .build()
    }

    private fun showExportToast(summary: UsagiBackupExportSummary) {
        Toast.makeText(
            this,
            getString(R.string.export_usagi_backup_saved, summary.exportedCount),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun IntentJobContext.showExportResultNotification(
        fileUri: Uri,
        summary: UsagiBackupExportSummary,
    ) {
        if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
            return
        }
        val shareIntent = ShareCompat.IntentBuilder(this@UsagiBackupExportService)
            .setStream(fileUri)
            .setType("application/zip")
            .setChooserTitle(R.string.share_backup)
            .createChooserIntent()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.export_usagi_backup))
            .setContentText(getString(R.string.export_usagi_backup_saved, summary.exportedCount))
            .setSubText(contentResolver.getFileDisplayName(fileUri))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setSilent(true)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentIntent(
                PendingIntentCompat.getActivity(
                    applicationContext,
                    0,
                    AppRouter.homeIntent(this@UsagiBackupExportService),
                    0,
                    false,
                ),
            )
            .addAction(
                appcompatR.drawable.abc_ic_menu_share_mtrl_alpha,
                getString(R.string.share),
                PendingIntentCompat.getActivity(this@UsagiBackupExportService, 0, shareIntent, 0, false),
            )
            .build()
        notificationManager.notify(notificationTag, startId, notification)
    }

    companion object {
        private const val TAG = "USAGI_BACKUP_EXPORT"
        private const val FOREGROUND_NOTIFICATION_ID = 43

        @CheckResult
        fun start(context: Context, uri: Uri): Boolean = try {
            val intent = Intent(context, UsagiBackupExportService::class.java)
            intent.putExtra(AppRouter.KEY_DATA, uri.toString())
            intent.setData(uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            e.printStackTraceDebug()
            false
        }
    }
}
