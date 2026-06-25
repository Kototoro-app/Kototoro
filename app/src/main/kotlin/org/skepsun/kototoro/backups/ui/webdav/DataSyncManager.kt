package org.skepsun.kototoro.backups.ui.webdav

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupFlowPolicy
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.logBackupFlow
import org.skepsun.kototoro.core.util.ext.connectivityManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 监听代表用户跨设备状态的关键表变更，按需进行 WebDAV 自动同步上传。
 * 避免监听元数据类高频表，减少使用过程中的无意义调度。
 */
@Singleton
class DataSyncManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val backupFlowPolicy: BackupFlowPolicy,
    private val repository: BackupRepository,
    private val backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator,
    private val externalBackupStorage: ExternalBackupStorage,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null
    private var settingsJob: Job? = null
    private val uploadMutex = Mutex()
    private var lastMinIntervalSkipLogAtMs: Long = 0L
    @Volatile
    private var isObserverRegistered = false

    private companion object {
        private const val TAG = "DataSyncManager"
        // 自动同步的最小间隔，防止过于频繁的上传（12 小时）
        private const val AUTO_SYNC_MIN_INTERVAL_MS: Long = 12L * 60L * 60_000L
        // 去抖动聚合时长保持 30 秒
        private const val AUTO_SYNC_DEBOUNCE_MS: Long = 30_000
    }

    private val tablesToObserve = arrayOf(
        TABLE_WORK_HISTORY,
        TABLE_WORK_FAVOURITES,
        TABLE_FAVOURITE_CATEGORIES,
        TABLE_ENTITY_GRAPH_RELATION,
        TABLE_ENTITY_PREFERENCES,
    )

    private val observer = object : InvalidationTracker.Observer(tablesToObserve) {
        override fun onInvalidated(tables: Set<String>) {
            scheduleUpload()
        }
    }

    /** 启动监听（幂等） */
    fun start() {
        if (settingsJob != null) {
            syncObserverRegistration(trigger = "start_reused")
            return
        }
        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "observer_manager_started")
        settingsJob = scope.launch {
            settings.observe(
                AppSettings.KEY_BACKUP_WEBDAV_ENABLED,
                AppSettings.KEY_BACKUP_WEBDAV_AUTO_SYNC,
                AppSettings.KEY_BACKUP_WEBDAV_URL,
                AppSettings.KEY_BACKUP_WEBDAV_USERNAME,
                AppSettings.KEY_BACKUP_WEBDAV_PASSWORD,
                AppSettings.KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE,
                AppSettings.KEY_WORK_MIGRATION_SYNC_WRITE_BLOCKED,
            ).collect { changedKey ->
                syncObserverRegistration(trigger = changedKey ?: "initial")
            }
        }
    }

    /** 停止监听并取消任务 */
    fun stop() {
        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "observer_manager_stopped")
        settingsJob?.cancel()
        settingsJob = null
        unregisterObserver(reason = "stop")
        debounceJob?.cancel()
    }

    fun requestImmediateUpload(reason: String) {
        val decision = backupFlowPolicy.autoSyncUploadDecision()
        if (!decision.allowed) {
            logBackupFlow(
                TAG,
                flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD,
                event = "force_request_skipped",
                reason = decision.reason,
                "trigger" to reason,
            )
            return
        }
        debounceJob?.cancel()
        logBackupFlow(
            TAG,
            flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD,
            event = "force_requested",
            reason = null,
            "trigger" to reason,
        )
        scope.launch {
            runCatching { uploadNow(force = true) }.onFailure { it.printStackTraceDebug() }
        }
    }

    private fun scheduleUpload() {
        val decision = backupFlowPolicy.autoSyncUploadDecision()
        if (!decision.allowed) {
            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "schedule_skipped", reason = decision.reason)
            return
        }

        // 收紧策略：若距离上次上传不足最小间隔，则跳过本次调度
        val lastUpload = settings.backupWebDavLastUploadTime
        if (lastUpload > 0L && System.currentTimeMillis() - lastUpload < AUTO_SYNC_MIN_INTERVAL_MS) {
            val now = System.currentTimeMillis()
            if (now - lastMinIntervalSkipLogAtMs >= AUTO_SYNC_DEBOUNCE_MS) {
                lastMinIntervalSkipLogAtMs = now
                logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "schedule_skipped", reason = "min_interval")
            }
            return
        }

        debounceJob?.cancel()
        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "scheduled", reason = null, "debounceMs" to AUTO_SYNC_DEBOUNCE_MS)
        debounceJob = scope.launch {
            // 聚合 30 秒内的变更
            delay(AUTO_SYNC_DEBOUNCE_MS)
            runCatching { uploadNow(force = false) }.onFailure { it.printStackTraceDebug() }
        }
    }

    private fun syncObserverRegistration(trigger: String) {
        val decision = backupFlowPolicy.autoSyncUploadDecision()
        if (decision.allowed) {
            if (isObserverRegistered) {
                return
            }
            runCatching {
                database.invalidationTracker.addObserver(observer)
                isObserverRegistered = true
                logBackupFlow(
                    TAG,
                    flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD,
                    event = "observer_registered",
                    reason = null,
                    "trigger" to trigger,
                )
            }.onFailure { it.printStackTraceDebug() }
            return
        }
        unregisterObserver(reason = decision.reason ?: "policy_block", trigger = trigger)
    }

    private fun unregisterObserver(reason: String, trigger: String? = null) {
        debounceJob?.cancel()
        debounceJob = null
        if (!isObserverRegistered) {
            return
        }
        runCatching {
            database.invalidationTracker.removeObserver(observer)
            isObserverRegistered = false
            logBackupFlow(
                TAG,
                flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD,
                event = "observer_unregistered",
                reason = reason,
                "trigger" to trigger,
            )
        }.onFailure { it.printStackTraceDebug() }
    }

    private suspend fun uploadNow(force: Boolean = false) {
        val decision = backupFlowPolicy.autoSyncUploadDecision()
        if (!decision.allowed) return

        // 收紧策略：仅在非计量网络上进行自动同步，且避免后台网络受限情形
        val cm = appContext.connectivityManager
        if (cm.isActiveNetworkMetered) {
            // 计量网络下不进行自动上传
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                return
            }
        }

        // 非强制触发时遵守最小间隔
        if (!force) {
            val lastUpload = settings.backupWebDavLastUploadTime
            if (lastUpload > 0L && System.currentTimeMillis() - lastUpload < AUTO_SYNC_MIN_INTERVAL_MS) return
        }

        uploadMutex.withLock {
            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "upload_start", reason = null, "force" to force)
            val output = BackupUtils.createTempFile(appContext)
            try {
                ZipOutputStream(output.outputStream()).use {
                    repository.createBackup(it, null)
                }
                // 按设置保留本地副本
                if (settings.isBackupWebDavKeepLocalCopyEnabled) {
                    runCatching {
                        externalBackupStorage.put(output)
                        externalBackupStorage.trim(settings.periodicalBackupMaxCount)
                    }.onFailure { it.printStackTraceDebug() }
                }
                val uploadResult = backupWebDavUploadCoordinator.uploadAndCommit(
                    file = output,
                    uploadKind = "auto",
                )
                logBackupFlow(
                    TAG,
                    flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD,
                    event = "upload_complete",
                    reason = null,
                    "force" to force,
                    "nextVersion" to uploadResult.targetVersion,
                )
            } finally {
                output.delete()
            }
        }
    }
}
