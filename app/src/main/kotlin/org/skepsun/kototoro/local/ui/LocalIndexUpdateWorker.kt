package org.skepsun.kototoro.local.ui

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
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import java.util.concurrent.TimeUnit

/**
 * Refreshes the local content index on startup, replacing the former
 * `LocalIndexUpdateService` service (deleted). A service started from the first-resume
 * lifecycle dispatch crashes with `BackgroundServiceStartNotAllowedException`
 * when the uid record still carries a stale background procstate (process
 * restart after being idle in background); WorkManager has no such restriction.
 */
@HiltWorker
class LocalIndexUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val localContentIndex: LocalContentIndex,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            localContentIndex.update()
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
        private const val UNIQUE_WORK_NAME = "local_index_update"

        suspend fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalIndexUpdateWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
                .await()
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<LocalIndexUpdateWorker>
}
