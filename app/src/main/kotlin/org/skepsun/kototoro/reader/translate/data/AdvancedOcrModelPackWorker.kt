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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.trySetForeground
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class AdvancedOcrModelPackWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted workerParams: WorkerParameters,
	private val modelManager: OnnxModelManager,
	private val settings: AppSettings,
) : CoroutineWorker(appContext, workerParams) {
	private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }

	override suspend fun doWork(): Result {
		trySetForeground()
		return try {
			REQUIRED_MODEL_IDS.forEachIndexed { index, modelId ->
				val model = OnnxOfficialModelCatalog.findById(modelId) ?: return Result.failure()
				modelManager.ensureModelReady(model) { progress ->
					val itemPercent = if (progress.totalBytes > 0L) {
						(progress.downloadedBytes * 100L / progress.totalBytes).toInt().coerceIn(0, 100)
					} else {
						0
					}
					val overallPercent = ((index * 100) + itemPercent) / REQUIRED_MODEL_IDS.size
					setProgressAsync(
						workDataOf(
							KEY_MODEL_INDEX to index,
							KEY_MODEL_ID to modelId,
							KEY_PROGRESS_PERCENT to overallPercent,
						),
					)
					if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
						notificationManager.notify(NOTIFICATION_ID, createNotification(overallPercent))
					}
				}
			}
			if (isStopped || !areAllModelsReady(modelManager)) return Result.failure()
			settings.readerTranslationOcrMode = ReaderOcrMode.ADVANCED
			Result.success()
		} catch (e: IOException) {
			e.printStackTrace()
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
		val notification = createNotification(0)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(NOTIFICATION_ID, notification)
		}
	}

	private fun createNotification(progressPercent: Int) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		.setContentTitle(applicationContext.getString(R.string.reader_translation_ocr_pack_title))
		.setContentText(applicationContext.getString(R.string.reader_translation_model_downloading_percent, progressPercent))
		.setSmallIcon(android.R.drawable.stat_sys_download)
		.setOnlyAlertOnce(true)
		.setOngoing(true)
		.setSilent(true)
		.setCategory(NotificationCompat.CATEGORY_PROGRESS)
		.setPriority(NotificationCompat.PRIORITY_LOW)
		.setProgress(100, progressPercent, false)
		.addAction(
			NotificationCompat.Action(
				android.R.drawable.ic_menu_close_clear_cancel,
				applicationContext.getString(android.R.string.cancel),
				WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
			),
		)
		.build()

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
	interface Factory : WorkerAssistedFactory<AdvancedOcrModelPackWorker>

	companion object {
		const val UNIQUE_WORK_NAME = "advanced_ocr_model_pack"
		const val KEY_MODEL_INDEX = "model_index"
		const val KEY_MODEL_ID = "model_id"
		const val KEY_PROGRESS_PERCENT = "progress_percent"
		private const val CHANNEL_ID = "advanced_ocr_model_pack"
		private const val NOTIFICATION_ID = 0x0C12

		val REQUIRED_MODEL_IDS = listOf(
			"manga_default_det_20241225_onnx",
			"mangaocr_2025_onnx",
			"ppocrv6_medium_rec_onnx",
			"latin_ppocrv5_mobile_rec_onnx",
			"korean_ppocrv5_mobile_rec_onnx",
			"thai_ppocrv5_mobile_rec_onnx",
		)

		fun areAllModelsReady(modelManager: OnnxModelManager): Boolean {
			return REQUIRED_MODEL_IDS.all(modelManager::isModelDownloaded)
		}

		fun enqueue(context: Context) {
			val request = OneTimeWorkRequestBuilder<AdvancedOcrModelPackWorker>()
				.setConstraints(
					Constraints.Builder()
						.setRequiredNetworkType(NetworkType.CONNECTED)
						.build(),
				)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			)
		}

		fun cancel(context: Context) {
			WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
		}

		fun isRunning(workInfo: WorkInfo?): Boolean {
			return workInfo?.state == WorkInfo.State.ENQUEUED || workInfo?.state == WorkInfo.State.RUNNING
		}
	}
}
