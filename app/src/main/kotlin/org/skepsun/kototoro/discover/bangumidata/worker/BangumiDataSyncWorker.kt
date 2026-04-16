package org.skepsun.kototoro.discover.bangumidata.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.util.ext.awaitUniqueWorkInfoByName
import androidx.work.await
import org.skepsun.kototoro.discover.bangumidata.data.BangumiDataRepository
import org.skepsun.kototoro.settings.work.PeriodicWorkScheduler
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import org.skepsun.kototoro.core.network.BaseHttpClient

@HiltWorker
class BangumiDataSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    @BaseHttpClient private val httpClient: OkHttpClient,
    private val repository: BangumiDataRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(DATA_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.retry()
            }

            val body = response.body ?: return@withContext Result.failure()

            val tempFile = File(context.filesDir, "bangumi-data.tmp")
            val finalFile = File(context.filesDir, "bangumi-data.json")

            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                tempFile.renameTo(finalFile)
                repository.invalidateCache()
                Result.success()
            } else {
                tempFile.delete()
                Result.failure()
            }
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }
    
    companion object {
        const val WORK_NAME = "BangumiDataSyncWorker"
        const val DATA_URL = "https://unpkg.com/bangumi-data@0.3/dist/data.json"
    }

    @AssistedFactory
    interface Factory : androidx.hilt.work.WorkerAssistedFactory<BangumiDataSyncWorker>

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager
    ) : PeriodicWorkScheduler {
        override suspend fun schedule() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BangumiDataSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
                .build()

            workManager
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                .await()
        }

        override suspend fun unschedule() {
            workManager.cancelUniqueWork(WORK_NAME).await()
        }

        override suspend fun isScheduled(): Boolean {
            return workManager.awaitUniqueWorkInfoByName(WORK_NAME).any { !it.state.isFinished }
        }
    }
}
