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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupFlowPolicy
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_CHAPTERS
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_SOURCES
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.logBackupFlow
import org.skepsun.kototoro.core.util.ext.connectivityManager
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 监听数据库关键表的变更，按需进行 WebDAV 自动同步上传。
 * 使用去抖动策略聚合短时间内的多次变更，避免频繁写入与上传。
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

    private companion object {
        private const val TAG = "DataSyncManager"
        // 自动同步的最小间隔，防止过于频繁的上传（12 小时）
        private const val AUTO_SYNC_MIN_INTERVAL_MS: Long = 12L * 60L * 60_000L
        // 去抖动聚合时长保持 30 秒
        private const val AUTO_SYNC_DEBOUNCE_MS: Long = 30_000
    }

    private val tablesToObserve = arrayOf(
        TABLE_HISTORY,
        TABLE_FAVOURITES,
        TABLE_FAVOURITE_CATEGORIES,
        TABLE_MANGA,
        TABLE_TAGS,
        TABLE_MANGA_TAGS,
        TABLE_SOURCES,
        TABLE_CHAPTERS,
        TABLE_ENTITY_GRAPH_ENTITY,
        TABLE_ENTITY_GRAPH_BINDING,
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
        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "observer_started")
        runCatching {
            database.invalidationTracker.addObserver(observer)
        }.onFailure { it.printStackTraceDebug() }

        // 监听数据版本变化：发生变化时跳过节流，立即执行一次上传
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settings.observe(AppSettings.KEY_BACKUP_WEBDAV_DATA_VERSION).collect { key ->
                if (key == AppSettings.KEY_BACKUP_WEBDAV_DATA_VERSION) {
                    // 数据版本变化时立即上传，忽略最小间隔限制
                    logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "version_changed_force_upload")
                    runCatching { uploadNow(force = true) }.onFailure { it.printStackTraceDebug() }
                }
            }
        }
    }

    /** 停止监听并取消任务 */
    fun stop() {
        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_SYNC_UPLOAD, event = "observer_stopped")
        runCatching {
            database.invalidationTracker.removeObserver(observer)
        }.onFailure { it.printStackTraceDebug() }
        debounceJob?.cancel()
        settingsJob?.cancel()
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
