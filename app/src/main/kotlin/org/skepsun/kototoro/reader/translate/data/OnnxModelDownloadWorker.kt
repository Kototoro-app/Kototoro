package org.skepsun.kototoro.reader.translate.data

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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.trySetForeground
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class OnnxModelDownloadWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted workerParams: WorkerParameters,
	private val modelManager: OnnxModelManager,
) : CoroutineWorker(appContext, workerParams) {

	private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }

	override suspend fun doWork(): Result {
		val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
		val model = OnnxOfficialModelCatalog.findById(modelId) ?: return Result.failure()
		trySetForeground()
		return try {
			modelManager.ensureModelReady(model) { progress ->
				setProgressAsync(
					workDataOf(
						KEY_MODEL_ID to model.id,
						KEY_DOWNLOADED_BYTES to progress.downloadedBytes,
						KEY_TOTAL_BYTES to progress.totalBytes,
					),
				)
				if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
					notificationManager.notify(notificationId(model.id), createNotification(model, progress))
				}
			}
			Result.success()
		} catch (e: IOException) {
			e.printStackTrace()
			Result.retry()
		} catch (e: Throwable) {
			e.printStackTrace()
			Result.failure()
		} finally {
			notificationManager.cancel(notificationId(model.id))
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		createNotificationChannel()
		val model = OnnxOfficialModelCatalog.findById(inputData.getString(KEY_MODEL_ID))
		val notification = createNotification(model, OnnxModelManager.DownloadProgress(0L, 0L))
		val id = notificationId(model?.id.orEmpty())
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(id, notification)
		}
	}

	private fun createNotification(
		model: OnnxOfficialModel?,
		progress: OnnxModelManager.DownloadProgress,
	) = NotificationCompat.Builder(applicationContext, CHANNEL_ID).apply {
		val title = model?.title ?: applicationContext.getString(R.string.reader_translation_ocr_models_title)
		val indeterminate = progress.totalBytes <= 0L
		setContentTitle(title)
		setContentText(
			if (indeterminate) {
				applicationContext.getString(R.string.reader_translation_paddle_download_starting)
			} else {
				applicationContext.getString(
					R.string.reader_translation_model_downloading_percent,
					(progress.downloadedBytes * 100L / progress.totalBytes).toInt().coerceIn(0, 100),
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
			if (indeterminate) 0 else PROGRESS_MAX,
			if (indeterminate) 0 else (progress.downloadedBytes * PROGRESS_MAX / progress.totalBytes).toInt().coerceIn(0, PROGRESS_MAX),
			indeterminate,
		)
		addAction(
			NotificationCompat.Action(
				android.R.drawable.ic_menu_close_clear_cancel,
				applicationContext.getString(android.R.string.cancel),
				WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
			),
		)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
		}
	}.build()

	private fun createNotificationChannel() {
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(applicationContext.getString(R.string.reader_translation_ocr_models_title))
			.setDescription(applicationContext.getString(R.string.reader_translation_model_download_channel_description))
			.setShowBadge(false)
			.setVibrationEnabled(false)
			.setLightsEnabled(false)
			.setSound(null, null)
			.build()
		notificationManager.createNotificationChannel(channel)
	}

	@AssistedFactory
	interface Factory : WorkerAssistedFactory<OnnxModelDownloadWorker>

	companion object {
		const val KEY_MODEL_ID = "model_id"
		const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
		const val KEY_TOTAL_BYTES = "total_bytes"
		private const val CHANNEL_ID = "onnx_model_downloads"
		private const val UNIQUE_WORK_PREFIX = "onnx_model_download:"
		private const val PROGRESS_MAX = 10_000

		fun uniqueWorkName(modelId: String): String {
			return UNIQUE_WORK_PREFIX + modelId
		}

		fun enqueue(context: Context, modelId: String) {
			val request = OneTimeWorkRequestBuilder<OnnxModelDownloadWorker>()
				.setInputData(workDataOf(KEY_MODEL_ID to modelId))
				.setConstraints(
					Constraints.Builder()
						.setRequiredNetworkType(NetworkType.CONNECTED)
						.build(),
				)
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.addTag(uniqueWorkName(modelId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				uniqueWorkName(modelId),
				ExistingWorkPolicy.KEEP,
				request,
			)
		}

		fun cancel(context: Context, modelId: String) {
			WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(modelId))
		}

		fun isRunning(workInfo: WorkInfo?): Boolean {
			return workInfo?.state == WorkInfo.State.ENQUEUED || workInfo?.state == WorkInfo.State.RUNNING
		}

		private fun notificationId(modelId: String): Int {
			return (CHANNEL_ID + modelId).hashCode()
		}
	}
}
