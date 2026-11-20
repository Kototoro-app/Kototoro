package org.skepsun.kototoro.community.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.community.domain.CommunityRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Publishes what the community outbox still holds once the device is online, and keeps trying with WorkManager's
 * backoff while anything is left (plan §7). Queued after any publish that did not empty the outbox; one at a time.
 */
@HiltWorker
class CommunityOutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: CommunityRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!repository.isEnabled) return Result.success()
        return if (repository.flushOutbox() == 0) Result.success() else Result.retry()
    }

    @Reusable
    class Scheduler @Inject constructor(private val workManager: WorkManager) {

        fun enqueue() {
            val request = OneTimeWorkRequestBuilder<CommunityOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            // KEEP: a worker already waiting will publish this event too.
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }

    private companion object {
        const val TAG = "community_outbox"
    }
}
