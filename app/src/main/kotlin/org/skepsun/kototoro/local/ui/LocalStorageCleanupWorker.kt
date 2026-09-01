package org.skepsun.kototoro.local.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.domain.DeleteReadChaptersUseCase
import java.util.concurrent.TimeUnit

@HiltWorker
class LocalStorageCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: AppSettings,
    private val localContentRepository: LocalMangaRepository,
    private val dataRepository: ContentDataRepository,
    private val deleteReadChaptersUseCase: DeleteReadChaptersUseCase,
    private val entityGraphRepository: EntityGraphRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (settings.isAutoLocalChaptersCleanupEnabled) {
            deleteReadChaptersUseCase.invoke()
        }
        retryPendingEntityConsolidation()
        repairPreferredLocalProjections()
        repairWorkStateAnchors()
        dataRepository.cleanupDatabase()
        return if (localContentRepository.cleanup()) {
            dataRepository.cleanupLocalContent()
            Result.success()
        } else {
            Result.retry()
        }
    }

    /**
     * 修复 anchor 与本地绑定漂移出来的收藏行（issue #510）：一旦库里出现这种行，
     * 分类里就会躺着「其实没收藏」的作品——列表按 anchor 画卡片，收藏状态按绑定判断。
     */
    private suspend fun repairWorkStateAnchors() {
        val repaired = entityGraphRepository.repairDetachedWorkStateAnchors()
        if (repaired > 0) {
            Log.w(TAG, "Repaired $repaired work favourites rows with a detached anchor")
        }
    }

    /**
     * 修复「显示投影」漂移（issue #510 的另一半）：`entity_preferences.preferred_local_manga_id`
     * 指向了不属于本实体、已失效或已删除的投影。收藏/历史 SQL 原样拿它当显示行来排序和过滤，
     * 而 Kotlin 侧会拒绝它并改用别的投影——于是卡片显示的是 A，排序按的是 B，
     * 任何一次刷新都会把列表重新洗一遍。
     */
    private suspend fun repairPreferredLocalProjections() {
        val repaired = entityGraphRepository.repairOrphanPreferredLocalProjections()
        if (repaired > 0) {
            Log.w(TAG, "Repaired $repaired entity_preferences rows with an orphan preferred projection")
        }
    }

    /**
     * 外部备份导入后「同名作品实体合并」失败过的重试钩子（issue #510）。
     * 合并没跑完时，同一部作品会以多个 WORK 实体的形式重复出现在收藏/分类里。
     */
    private suspend fun retryPendingEntityConsolidation() {
        if (!settings.isEntityConsolidationPending) {
            return
        }
        runCatching { entityGraphRepository.consolidateImportProvisionalEntities() }
            .onSuccess {
                settings.isEntityConsolidationPending = false
                Log.i(TAG, "Retried pending entity consolidation")
            }
            .onFailure { e ->
                Log.w(TAG, "Pending entity consolidation retry failed again", e)
            }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = applicationContext.getString(R.string.local_storage_cleanup)
        val channel = NotificationChannelCompat.Builder(WORKER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(title)
            .setShowBadge(true)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .setLightsEnabled(true)
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentIntent(
                PendingIntentCompat.getActivity(
                    applicationContext,
                    0,
                    AppRouter.suggestionsSettingsIntent(applicationContext),
                    0,
                    false,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDefaults(0)
            .setOngoing(false)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val actionIntent = PendingIntentCompat.getActivity(
                applicationContext, SETTINGS_ACTION_CODE,
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, WORKER_CHANNEL_ID),
                0, false,
            )
            notification.addAction(
                R.drawable.ic_settings,
                applicationContext.getString(R.string.notifications_settings),
                actionIntent,
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build())
        }
    }

    companion object {

        private const val TAG = "cleanup"
        private const val WORKER_CHANNEL_ID = "storage_cleanup"
        private const val WORKER_NOTIFICATION_ID = 32
        private const val SETTINGS_ACTION_CODE = 6

        suspend fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalStorageCleanupWorker>()
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request).await()
        }
    }

    @AssistedFactory
    interface Factory : WorkerAssistedFactory<LocalStorageCleanupWorker>
}
