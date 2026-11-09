package org.skepsun.kototoro.sync.google.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.sync.google.ui.syncStageLabel
import org.skepsun.kototoro.settings.work.PeriodicWorkScheduler
import org.skepsun.kototoro.core.util.ext.awaitUniqueWorkInfoByName
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncRepository
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncResult
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class GoogleDriveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: GoogleDriveSyncRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = coroutineScope {
        trySetForegroundInfo(null)
        val stageJob = launch {
            repository.syncStage.collect { stage -> trySetForegroundInfo(stage) }
        }
        try {
            when (val result = repository.sync()) {
                is GoogleDriveSyncResult.Success -> Result.success()
                is GoogleDriveSyncResult.AuthorizationRequired -> Result.failure()
                is GoogleDriveSyncResult.Disabled -> Result.success()
                is GoogleDriveSyncResult.Error -> {
                    if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        } finally {
            stageJob.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(null)

    private suspend fun trySetForegroundInfo(stage: String?) {
        try {
            setForeground(buildForegroundInfo(stage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GoogleDriveSyncWorker", "foreground info failed", e)
        }
    }

    private fun buildForegroundInfo(stage: String?): ForegroundInfo {
        val context = applicationContext
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.google_drive_sync))
                .build(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(stage?.let { context.syncStageLabel(it) } ?: context.getString(R.string.sync_syncing))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDefaults(0)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setSmallIcon(R.drawable.ic_sync)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
        private val settings: GoogleDriveSyncSettings,
        private val repository: GoogleDriveSyncRepository,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val intervalMinutes = settings.intervalMinutes
            if (!settings.isSyncEnabled || !settings.isSignedIn || intervalMinutes <= 0) {
                unschedule()
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<GoogleDriveSyncWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setInitialDelay(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .addTag(TAG_PERIODIC)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(TAG_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request).await()
        }

        override suspend fun unschedule() {
            workManager.cancelUniqueWork(TAG_PERIODIC).await()
        }

        override suspend fun isScheduled(): Boolean {
            return workManager.awaitUniqueWorkInfoByName(TAG_PERIODIC).any { !it.state.isFinished }
        }

        fun enqueueManual() {
            if (!settings.isSyncEnabled) {
                return
            }
            val request = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG_MANUAL)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueStartSyncIfAllowed() {
            if (repository.shouldSyncOnStart()) {
                enqueueManual()
            }
        }
    }

    interface Factory : WorkerAssistedFactory<GoogleDriveSyncWorker>

    companion object {

        private const val TAG_PERIODIC = "google_drive_sync_periodic"
        private const val TAG_MANUAL = "google_drive_sync_manual"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "google_drive_sync"
        private const val NOTIFICATION_ID = 2002
    }
}
