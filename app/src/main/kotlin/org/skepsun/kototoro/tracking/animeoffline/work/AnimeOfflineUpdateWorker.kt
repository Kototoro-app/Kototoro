package org.skepsun.kototoro.tracking.animeoffline.work

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
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.trySetForeground
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class AnimeOfflineUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: AnimeOfflineRepository,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }

    override suspend fun doWork(): Result {
        trySetForeground()
        repository.recordCheck()
        val latest = repository.fetchLatestRelease() ?: return Result.success()
        if (!repository.isUpdateRequired(latest)) {
            return Result.success()
        }
        return try {
            repository.downloadAndInstall(latest) { downloadedBytes, totalBytes ->
                this.setProgress(
                    workDataOf(
                        KEY_DOWNLOADED_BYTES to downloadedBytes,
                        KEY_TOTAL_BYTES to totalBytes,
                    ),
                )
                if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
                    notificationManager.notify(NOTIFICATION_ID, createNotification(downloadedBytes, totalBytes))
                }
            }
            Result.success()
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (e: Throwable) {
            e.printStackTrace()
            Result.failure()
        } finally {
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification(0L, 0L)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(downloadedBytes: Long, totalBytes: Long) = NotificationCompat.Builder(
        applicationContext,
        CHANNEL_ID,
    ).apply {
        val indeterminate = totalBytes <= 0L
        setContentTitle(applicationContext.getString(R.string.anime_offline_database_update_title))
        setContentText(
            if (indeterminate) {
                applicationContext.getString(R.string.anime_offline_database_update_checking)
            } else {
                applicationContext.getString(
                    R.string.anime_offline_database_update_progress,
                    (downloadedBytes / 1024 / 1024).toInt(),
                    (totalBytes / 1024 / 1024).toInt().coerceAtLeast(1),
                )
            },
        )
        setSmallIcon(android.R.drawable.stat_sys_download)
        setOnlyAlertOnce(true)
        setOngoing(true)
        setSilent(true)
        setCategory(NotificationCompat.CATEGORY_PROGRESS)
        setPriority(NotificationCompat.PRIORITY_LOW)
        setProgress(
            if (indeterminate) 0 else totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            downloadedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            indeterminate,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
    }.build()

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(applicationContext.getString(R.string.anime_offline_database_update_title))
            .setDescription(applicationContext.getString(R.string.anime_offline_database_update_channel_description))
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .setLightsEnabled(false)
            .setSound(null, null)
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<AnimeOfflineUpdateWorker>

    companion object {
        const val UNIQUE_WORK_NAME = "anime_offline_database_update"
        private const val CHANNEL_ID = "anime_offline_database_update"
        private const val NOTIFICATION_ID = 44231
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnimeOfflineUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
