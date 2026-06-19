package org.skepsun.kototoro.backups.ui.webdav

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.core.util.ext.trySetForeground
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class WebDavAutoRestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: WebDavAutoRestoreRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        trySetForeground()
        return runCatching {
            runner.run()
            Result.success()
        }.getOrElse { error ->
            if (error.isRetryableAutoRestoreFailure()) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        BaseBackupRestoreService.createNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, BaseBackupRestoreService.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.webdav_auto_restore))
            .setContentText(applicationContext.getString(R.string.checking_for_backups))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDefaults(0)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setSmallIcon(R.drawable.ic_backup_restore)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "webdav_auto_restore"
        private const val NOTIFICATION_ID = 2001

        suspend fun enqueue(context: Context, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<WebDavAutoRestoreWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
                .await()
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<WebDavAutoRestoreWorker>
}

private fun Throwable.isRetryableAutoRestoreFailure(): Boolean {
    return this is IOException || cause is IOException
}
