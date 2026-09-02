package org.skepsun.kototoro.browser

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.core.network.webview.adblock.AdBlock
import java.util.concurrent.TimeUnit

/**
 * Refreshes the ad block filter list on startup, replacing the former
 * `AdListUpdateService` service (deleted). A service started from the first-resume
 * lifecycle dispatch crashes with `BackgroundServiceStartNotAllowedException`
 * when the uid record still carries a stale background procstate (process
 * restart after being idle in background); WorkManager has no such restriction.
 */
@HiltWorker
class AdListUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updater: AdBlock.Updater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            updater.updateList()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {

        private const val MAX_ATTEMPTS = 3

        /** Unique name so repeated enqueues collapse while one update is running. */
        private const val UNIQUE_WORK_NAME = "ad_list_update"

        suspend fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AdListUpdateWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
                .await()
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<AdListUpdateWorker>
}
