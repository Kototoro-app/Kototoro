package org.skepsun.kototoro.tracker.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
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
import org.skepsun.kototoro.tracker.domain.SourceAnswer
import org.skepsun.kototoro.tracker.domain.SourceCheck
import org.skepsun.kototoro.tracker.domain.checkBySource
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates
import org.skepsun.kototoro.tracker.work.TrackerNotificationHelper.NotificationInfo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR

/**
 * Result of asking [TrackWorker.Scheduler.requestCheckNow] to start a new-chapter check.
 * Every page that can trigger a manual check (favourites, updates, feed) shares the same
 * gate so an in-flight check is never restarted and a freshly-finished check is not
 * re-triggered within the cooldown window.
 */
sealed interface UpdateCheckRequest {
    /** A new one-shot check was started. */
    data object Started : UpdateCheckRequest

    /** A check is already queued/running (shared state) — the request was rejected. */
    data object InFlight : UpdateCheckRequest

    /** The last check finished less than the cooldown window ago — the request was rejected. */
    data object TooSoon : UpdateCheckRequest

    /** New-chapter tracking is disabled in settings — nothing was started. */
    data object TrackerDisabled : UpdateCheckRequest
}

/** The toast prompt a page shows for a given check request outcome. */
fun UpdateCheckRequest.messageRes(): Int = when (this) {
    UpdateCheckRequest.Started -> R.string.checking_for_updates
    UpdateCheckRequest.InFlight -> R.string.updates_check_in_progress
    UpdateCheckRequest.TooSoon -> R.string.updates_check_too_soon
    UpdateCheckRequest.TrackerDisabled -> R.string.check_for_new_chapters_disabled
}

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
        val sourceState = TrackSourceCooldowns(applicationContext)
        val tracks = getTracksUseCase(
            limit = if (isFullRun) Int.MAX_VALUE else BATCH_SIZE,
            cooldowns = sourceState.read(),
            challenged = sourceState.readChallenged(),
            // A run the user asked for checks everything; background runs only works due at their release pace.
            respectSchedule = !isFullRun,
        )
        if (tracks.isEmpty()) {
            return Result.success()
        }

        val notifications = checkUpdatesAsync(tracks, sourceState)
        if (notifications.isNotEmpty() && applicationContext.checkNotificationPermission(null)) {
            val groupNotification = notificationHelper.createGroupNotification(notifications)
            notifications.forEach { notificationManager.notify(it.tag, it.id, it.notification) }
            if (groupNotification != null) {
                notificationManager.notify(TAG, TrackerNotificationHelper.GROUP_NOTIFICATION_ID, groupNotification)
            }
        }
        return Result.success()
    }

    /**
     * Checks [tracks] taking sources in turn, with at most [TRACK_SOURCE_PARALLELISM] checks per source under
     * [TRACK_GLOBAL_PARALLELISM] overall, so a library on a few sites does not put every request on one of them.
     * A source that rate-limits is paused, and one that asks for a challenge or keeps refusing is skipped for
     * the rest of the run; skipped titles are not checked, so they keep their place for the next run. A source given
     * up on because of rate limits cools down, and later runs skip it until then; a source that challenged the run
     * is remembered, so the next batches give it only a probe until it answers again.
     */
    @CheckResult
    private suspend fun checkUpdatesAsync(
        tracks: List<ContentTracking>,
        sourceState: TrackSourceCooldowns,
    ): List<NotificationInfo> {
        val challenged = HashSet<String>()
        val answered = HashSet<String>()
        return try {
            checkUpdatesBySource(tracks, sourceState, challenged, answered)
        } finally {
            // Also after a cancelled run: what it heard from sources holds either way.
            sourceState.updateChallenged(challenged, answered)
        }
    }

    private suspend fun checkUpdatesBySource(
        tracks: List<ContentTracking>,
        sourceState: TrackSourceCooldowns,
        challenged: MutableSet<String>,
        answered: MutableSet<String>,
    ): List<NotificationInfo> {
        var tally = CheckTally()
        return checkBySource(
            items = tracks,
            sourceOf = { it.manga.source.name },
            now = System::currentTimeMillis,
            answerOf = { updates: MangaUpdates -> (updates as? MangaUpdates.Failure)?.error.toSourceAnswer() },
            cooldowns = sourceState.read(),
            onCooldown = sourceState::put,
            check = ::checkTrack,
        ).map { check ->
            when (check) {
                is SourceCheck.Skipped -> null
                is SourceCheck.Checked -> check.result.also { result ->
                    val answer = (result as? MangaUpdates.Failure)?.error.toSourceAnswer()
                    when (answer) {
                        SourceAnswer.Challenged -> challenged += check.source
                        SourceAnswer.Answered -> answered += check.source
                        is SourceAnswer.RateLimited -> Unit
                    }
                }
            }
        }.onEachIndexed { index, it ->
            tally = tally.add(it)
            if (applicationContext.checkNotificationPermission(WORKER_CHANNEL_ID)) {
                notificationManager.notify(
                    WORKER_NOTIFICATION_ID,
                    createWorkerNotification(tracks.size, index + 1, tally),
                )
            }
            when (it) {
                null -> Unit
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
                null, is MangaUpdates.Failure -> null
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

    private suspend fun checkTrack(track: ContentTracking): MangaUpdates = runCatchingCancellable {
        checkNewChaptersUseCase.invoke(track)
    }.getOrElse { error ->
        MangaUpdates.Failure(
            manga = track.manga,
            entityId = track.entityId,
            anchorMangaId = track.anchorMangaId,
            error = error,
        )
    }

    /** What the run has found so far, shown under the progress bar; null updates are titles skipped. */
    private data class CheckTally(val newChapters: Int = 0, val failed: Int = 0, val skipped: Int = 0) {

        fun add(updates: MangaUpdates?): CheckTally = when (updates) {
            null -> copy(skipped = skipped + 1)
            is MangaUpdates.Failure -> copy(failed = failed + 1)
            is MangaUpdates.Success -> if (updates.isValid) copy(newChapters = newChapters + updates.newChapters.size) else this
        }

        val isEmpty: Boolean get() = newChapters == 0 && failed == 0 && skipped == 0
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

    private fun createWorkerNotification(
        max: Int,
        progress: Int,
        tally: CheckTally = CheckTally(),
    ) = NotificationCompat.Builder(
        applicationContext,
        WORKER_CHANNEL_ID,
    ).apply {
        setContentTitle(applicationContext.getString(R.string.check_for_new_chapters))
        if (!tally.isEmpty) {
            setContentText(
                applicationContext.getString(
                    R.string.tracker_progress_summary,
                    tally.newChapters,
                    tally.failed,
                    tally.skipped,
                ),
            )
        }
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
        private val trackingRepositoryProvider: Provider<TrackingRepository>,
    ) : PeriodicWorkScheduler {

        private val requestMutex = Mutex()
        private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Elapsed realtime (see [SystemClock.elapsedRealtime]) of the last one-shot check
         * that finished successfully. Shared across every page; feeds the cooldown gate.
         */
        @Volatile
        private var lastFinishedRealtimeMs = 0L

        init {
            observerScope.launch { observeOneShotCompletions() }
        }

        override suspend fun schedule() {
            val frequency = settings.trackerFrequencyFactor
            // The interval rule is shared with the Windows scheduler (core-domain TrackRules).
            val interval = org.skepsun.kototoro.tracker.domain.trackerCheckIntervalHours(
                trackCount = trackingRepositoryProvider.get().getCheckedTracksCount(),
                batchSize = BATCH_SIZE,
                frequency = frequency,
            ) ?: return unschedule()
            val constraints = createConstraints()
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

        /**
         * Blocks until the one-shot update check enqueued by [startNow] reaches a finished
         * state (SUCCEEDED / FAILED / CANCELLED). Returns `false` when [timeoutMs] elapses
         * first — e.g. the device is offline and the work is still queued, or the library is
         * large and the worker continues in the background (it reports via its own
         * foreground notification).
         */
        suspend fun awaitOneShot(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val works = workManager.awaitUniqueWorkInfoByName(TAG_ONESHOT)
                if (works.isNotEmpty() && works.all { it.state.isFinished }) {
                    return true
                }
                delay(500)
            }
            return false
        }

        /**
         * Shared gate for every manual "check for new chapters" trigger (favourites,
         * updates and feed pages). Serializes concurrent requests, refuses to restart a
         * check that is already queued/running, and refuses to re-start one that finished
         * within [cooldownMs] (only successful runs feed the cooldown, so a failed check —
         * e.g. after a captcha resolution retry — can be retried immediately).
         */
        suspend fun requestCheckNow(
            cooldownMs: Long = DEFAULT_CHECK_COOLDOWN_MS,
        ): UpdateCheckRequest = requestMutex.withLock {
            when {
                !settings.isTrackerEnabled -> UpdateCheckRequest.TrackerDisabled
                isOneShotActive() -> UpdateCheckRequest.InFlight
                else -> {
                    val now = SystemClock.elapsedRealtime()
                    val sinceFinished = now - lastFinishedRealtimeMs
                    if (lastFinishedRealtimeMs != 0L && sinceFinished < cooldownMs) {
                        UpdateCheckRequest.TooSoon
                    } else {
                        startNow()
                        UpdateCheckRequest.Started
                    }
                }
            }
        }

        private suspend fun isOneShotActive(): Boolean {
            val works = workManager.awaitUniqueWorkInfoByName(TAG_ONESHOT)
            return works.any { !it.state.isFinished }
        }

        /**
         * Records the moment the one-shot check finishes successfully so that [requestCheckNow]
         * can apply its cooldown regardless of which page started the check (and even if the
         * starting page was destroyed while the worker kept running).
         */
        private suspend fun observeOneShotCompletions() {
            var wasRunning = false
            workManager.getWorkInfosForUniqueWorkFlow(TAG_ONESHOT)
                .map { infos ->
                    // `infos` may contain pruned history for the unique name; the current
                    // run is reflected by the non-finished entry.
                    infos.any { !it.state.isFinished } to infos.any { it.state == WorkInfo.State.SUCCEEDED }
                }
                .distinctUntilChanged()
                .collect { (running, anySucceeded) ->
                    if (wasRunning && !running && anySucceeded) {
                        lastFinishedRealtimeMs = SystemClock.elapsedRealtime()
                    }
                    wasRunning = running
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
        val BATCH_SIZE = if (BuildConfig.DEBUG) 20 else 46
        const val SETTINGS_ACTION_CODE = 5

        /**
         * Minimum pause between two manual new-chapter checks once the previous one has
         * finished. Shared by every page that can trigger a check.
         */
        const val DEFAULT_CHECK_COOLDOWN_MS = 60_000L
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<TrackWorker>
}
