package org.skepsun.kototoro.tracker.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.annotation.CheckResult
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Lazy
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaHandler
import org.skepsun.kototoro.core.model.ids
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TrackerDownloadStrategy
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.util.ext.awaitUniqueWorkInfoByName
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.onEachIndexed
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.trySetForeground
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.toIntUp
import org.skepsun.kototoro.settings.work.PeriodicWorkScheduler
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.tracker.domain.GetTracksUseCase
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates
import org.skepsun.kototoro.tracker.work.TrackerNotificationHelper.NotificationInfo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR

@HiltWorker
class TrackWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val captchaHandler: CaptchaHandler,
	private val notificationHelper: TrackerNotificationHelper,
	private val settings: AppSettings,
	private val getTracksUseCase: GetTracksUseCase,
	private val checkNewChaptersUseCase: CheckNewChaptersUseCase,
	private val workManager: WorkManager,
	private val contentDataRepository: ContentDataRepository,
	private val localRepositoryLazy: Lazy<LocalMangaRepository>,
	private val downloadSchedulerLazy: Lazy<DownloadWorker.Scheduler>,
) : CoroutineWorker(context, workerParams) {

	private data class AutoDownloadSeed(
		val executionManga: org.skepsun.kototoro.parsers.model.Content,
		val displayManga: org.skepsun.kototoro.parsers.model.Content?,
	)

	private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }

	override suspend fun doWork(): Result {
		notificationHelper.updateChannels()
		trySetForeground()
		return try {
			doWorkImpl(isFullRun = TAG_ONESHOT in tags)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			Result.failure()
		} finally {
			withContext(NonCancellable) {
				notificationManager.cancel(WORKER_NOTIFICATION_ID)
			}
		}
	}

	private suspend fun doWorkImpl(isFullRun: Boolean): Result {
		if (!settings.isTrackerEnabled) {
			return Result.success()
		}
		val tracks = getTracksUseCase(if (isFullRun) Int.MAX_VALUE else BATCH_SIZE)
		if (tracks.isEmpty()) {
			return Result.success()
		}

		val notifications = checkUpdatesAsync(tracks)
		if (notifications.isNotEmpty() && applicationContext.checkNotificationPermission(null)) {
			val groupNotification = notificationHelper.createGroupNotification(notifications)
			notifications.forEach { notificationManager.notify(it.tag, it.id, it.notification) }
			if (groupNotification != null) {
				notificationManager.notify(TAG, TrackerNotificationHelper.GROUP_NOTIFICATION_ID, groupNotification)
			}
		}
		return Result.success()
	}

	@CheckResult
	private suspend fun checkUpdatesAsync(tracks: List<ContentTracking>): List<NotificationInfo> {
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			for (track in tracks) {
				launch {
					semaphore.withPermit {
						send(
							runCatchingCancellable {
								checkNewChaptersUseCase.invoke(track)
							}.getOrElse { error ->
								MangaUpdates.Failure(
									manga = track.manga,
									entityId = track.entityId,
									anchorMangaId = track.anchorMangaId,
									error = error,
								)
							},
						)
					}
				}
			}
		}.onEachIndexed { index, it ->
			if (applicationContext.checkNotificationPermission(WORKER_CHANNEL_ID)) {
				notificationManager.notify(WORKER_NOTIFICATION_ID, createWorkerNotification(tracks.size, index + 1))
			}
			when (it) {
				is MangaUpdates.Failure -> {
					val e = it.error
					if (e is CloudFlareException) {
						// 后台追更检查只记录 Cloudflare 状态，不自动拉起验证码处理界面。
						captchaHandler.handle(e, tryAutoResolve = false)
					}
				}

				is MangaUpdates.Success -> processDownload(it)
			}
		}.mapNotNull {
			when (it) {
				is MangaUpdates.Failure -> null
				is MangaUpdates.Success -> if (it.isValid && it.isNotEmpty()) {
					notificationHelper.createNotification(
						manga = it.manga,
						newChapters = it.newChapters,
					)
				} else {
					null
				}
			}
		}.toList()
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val channel = NotificationChannelCompat.Builder(
			WORKER_CHANNEL_ID,
			NotificationManagerCompat.IMPORTANCE_LOW,
		)
			.setName(applicationContext.getString(R.string.check_for_new_chapters))
			.setShowBadge(false)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(false)
			.build()
		notificationManager.createNotificationChannel(channel)

		val notification = createWorkerNotification(0, 0)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
		}
	}

	private fun createWorkerNotification(max: Int, progress: Int) = NotificationCompat.Builder(
		applicationContext,
		WORKER_CHANNEL_ID,
	).apply {
		setContentTitle(applicationContext.getString(R.string.check_for_new_chapters))
		setPriority(NotificationCompat.PRIORITY_MIN)
		setCategory(NotificationCompat.CATEGORY_SERVICE)
		setDefaults(0)
		setOngoing(false)
		setOnlyAlertOnce(true)
		setSilent(true)
		setContentIntent(
			PendingIntentCompat.getActivity(
				applicationContext,
				0,
				AppRouter.trackerSettingsIntent(applicationContext),
				0,
				false,
			),
		)
		addAction(
			appcompatR.drawable.abc_ic_clear_material,
			applicationContext.getString(android.R.string.cancel),
			workManager.createCancelPendingIntent(id),
		)
		setProgress(max, progress, max == 0)
		setSmallIcon(android.R.drawable.stat_notify_sync)
		setForegroundServiceBehavior(
			if (TAG_ONESHOT in tags) {
				NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
			} else {
				NotificationCompat.FOREGROUND_SERVICE_DEFERRED
			},
		)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val actionIntent = PendingIntentCompat.getActivity(
				applicationContext, SETTINGS_ACTION_CODE,
				Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
					.putExtra(Settings.EXTRA_CHANNEL_ID, WORKER_CHANNEL_ID),
				0, false,
			)
			addAction(
				R.drawable.ic_settings,
				applicationContext.getString(R.string.notifications_settings),
				actionIntent,
			)
		}
	}.build()

	private suspend fun processDownload(mangaUpdates: MangaUpdates.Success) {
		if (!mangaUpdates.isValid || mangaUpdates.newChapters.isEmpty()) {
			return
		}
		when (settings.trackerDownloadStrategy) {
			TrackerDownloadStrategy.DISABLED -> Unit
			TrackerDownloadStrategy.DOWNLOADED -> {
				val downloadSeed = resolveAutoDownloadSeed(mangaUpdates)
				val localRepository = localRepositoryLazy.get()
				val localContent = downloadSeed.displayManga
					?.let { localRepository.findSavedContent(it) }
					?: localRepository.findSavedContent(downloadSeed.executionManga)
				if (localContent != null) {
					val storedExecutionManga = contentDataRepository.storeContentAndReturn(
						downloadSeed.executionManga,
						replaceExisting = true,
					)
					val storedDisplayManga = downloadSeed.displayManga?.let { displayManga ->
						contentDataRepository.storeContentAndReturn(displayManga, replaceExisting = false)
					}
					val displayMangaId = storedDisplayManga?.id ?: storedExecutionManga.id
					val task = DownloadTask.createExecutionTask(
						executionMangaId = storedExecutionManga.id,
						displayMangaId = displayMangaId,
						isPaused = false,
						isSilent = false,
						executionChapterIds = mangaUpdates.newChapters.ids().toLongArray(),
						executionChapterRefs = mangaUpdates.newChapters.map(ExecutionChapterRef::fromChapter),
						destination = null,
						format = null,
						allowMeteredNetwork = settings.allowDownloadOnMeteredNetwork != TriStateOption.DISABLED,
					)
					downloadSchedulerLazy.get().schedule(setOf(storedExecutionManga to task))
				}
			}
		}
	}

	private suspend fun resolveAutoDownloadSeed(mangaUpdates: MangaUpdates.Success): AutoDownloadSeed {
		val executionManga = contentDataRepository.findContentById(
			mangaUpdates.manga.id,
			withChapters = true,
		) ?: mangaUpdates.manga
		val displayManga = contentDataRepository.findDisplayContentById(
			executionManga.id,
			withChapters = false,
		)
		return AutoDownloadSeed(
			executionManga = executionManga,
			displayManga = displayManga,
		)
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val settings: AppSettings,
		private val dbProvider: Provider<MangaDatabase>,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val frequency = settings.trackerFrequencyFactor
			if (frequency <= 0f) {
				return unschedule()
			}
			val constraints = createConstraints()
			val runCount = dbProvider.get().getTracksDao().getTracksCount()
			val runsPerFullCheck = (runCount / BATCH_SIZE.toFloat()).toIntUp().coerceAtLeast(1)
			val interval = (18 / runsPerFullCheck / frequency).roundToInt().coerceAtLeast(2)
			val request = PeriodicWorkRequestBuilder<TrackWorker>(interval.toLong(), TimeUnit.HOURS)
				.setConstraints(constraints)
				.addTag(TAG)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
				.build()
			workManager
				.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
				.await()
		}

		override suspend fun unschedule() {
			workManager
				.cancelUniqueWork(TAG)
				.await()
		}

		override suspend fun isScheduled(): Boolean {
			return workManager
				.awaitUniqueWorkInfoByName(TAG)
				.any { !it.state.isFinished }
		}

		fun startNow() {
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = OneTimeWorkRequestBuilder<TrackWorker>()
				.setConstraints(constraints)
				.addTag(TAG_ONESHOT)
				.build()
			workManager.enqueueUniqueWork(TAG_ONESHOT, androidx.work.ExistingWorkPolicy.REPLACE, request)
		}

		fun observeIsRunning(): Flow<Boolean> {
			val query = WorkQuery.Builder.fromTags(listOf(TAG, TAG_ONESHOT)).build()
			return workManager.getWorkInfosFlow(query)
				.map { works ->
					works.any { work ->
						when {
							TAG_ONESHOT in work.tags -> !work.state.isFinished
							else -> work.state == WorkInfo.State.RUNNING
						}
					}
				}
		}

		private fun createConstraints() = Constraints.Builder()
			.setRequiredNetworkType(if (settings.isTrackerWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
			.build()
	}

	private companion object {

		const val WORKER_CHANNEL_ID = "track_worker"
		const val WORKER_NOTIFICATION_ID = 35
		const val TAG = "tracking"
		const val TAG_ONESHOT = "tracking_oneshot"
		const val MAX_PARALLELISM = 6
		val BATCH_SIZE = if (BuildConfig.DEBUG) 20 else 46
		const val SETTINGS_ACTION_CODE = 5
	}

	@AssistedFactory
	interface Factory : WorkerAssistedFactory<TrackWorker>
}
