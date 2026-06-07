package org.skepsun.kototoro.backups.ui.webdav

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupFlowPolicy
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.ui.BaseBackupRestoreService
import org.skepsun.kototoro.backups.ui.periodical.BackupFileInfo
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.logBackupFlow
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject

@AndroidEntryPoint
class WebDavAutoRestoreService : Service() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var backupRepository: BackupRepository

	@Inject
	lateinit var webDavUploader: WebDavBackupUploader

	@Inject
	lateinit var backupFlowPolicy: BackupFlowPolicy

	@Inject
	lateinit var backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator

	@Inject
	lateinit var backupWebDavRestoreCoordinator: BackupWebDavRestoreCoordinator

	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	override fun onCreate() {
		super.onCreate()
		BaseBackupRestoreService.createNotificationChannel(this)
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// 创建前台服务通知
		val notification = NotificationCompat.Builder(this, BaseBackupRestoreService.CHANNEL_ID)
			.setContentTitle(getString(R.string.webdav_auto_restore))
			.setContentText(getString(R.string.checking_for_backups))
			.setSmallIcon(R.drawable.ic_backup_restore)
			.setOngoing(true)
			.setSilent(true)
			.build()

		startForeground(NOTIFICATION_ID, notification)

		// 遵循开关并校验 WebDAV 配置有效性，避免错误
		val decision = backupFlowPolicy.autoRestoreStartupDecision()
		if (!decision.allowed) {
			logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "start_skipped", reason = decision.reason)
			stopSelf()
			return START_NOT_STICKY
		}

		// 检查策略：仅当每天第一次启动时执行（比较日期）
		val lastCheck = settings.backupWebDavLastAutoRestoreCheckTime
		val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
		if (lastCheck > 0 && df.format(Date(lastCheck)) == df.format(Date())) {
			logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "start_skipped", reason = "already_checked_today")
			stopSelf()
			return START_NOT_STICKY
		}

		serviceScope.launch {
			try {
				performAutoRestore()
			} catch (e: Exception) {
				Log.e(TAG, "Auto restore failed", e)
				e.printStackTraceDebug()
			} finally {
				stopSelf()
			}
		}

		return START_NOT_STICKY
	}

	override fun onDestroy() {
		super.onDestroy()
		serviceScope.cancel()
	}

    private suspend fun performAutoRestore() {
        val currentTime = System.currentTimeMillis()

        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_check_started")

        try {
            val remoteFiles = webDavUploader.listBackupFiles(RemoteNamespace.V2)
            if (remoteFiles.isEmpty()) {
                val legacy = selectLegacyCandidate()
                if (legacy == null) {
                    logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_skipped", reason = "no_remote_backups")
                    return
                }
                restoreCandidate(legacy, currentTime, isLegacyMigration = true)
                return
            }

            val candidate = selectPreferredCandidate(remoteFiles)
                ?: run {
                    logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_skipped", reason = "no_compatible_v2_backup")
                    return
                }
            restoreCandidate(candidate, currentTime, isLegacyMigration = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform auto restore", e)
            throw e
        }

        // 记录本次检查的时间（不再用于节流，仅用于状态展示）
        settings.backupWebDavLastAutoRestoreCheckTime = currentTime
    }

    private suspend fun restoreCandidate(
        candidate: BackupFileInfo,
        currentTime: Long,
        isLegacyMigration: Boolean,
    ) {
        logBackupFlow(
            TAG,
            flow = BackupFlow.WEBDAV_AUTO_RESTORE,
            event = "backup_selected",
            reason = null,
            "name" to candidate.name,
            "version" to candidate.dataVersion,
            "modified" to candidate.lastModified,
            "writerGeneration" to candidate.writerGeneration,
        )

        val tempFile = File.createTempFile("webdav_backup", ".bk.zip", this.cacheDir)
        try {
            Log.d(TAG, "Downloading backup file: ${candidate.name}")
            webDavUploader.downloadBackup(candidate.name, tempFile, candidate.namespace)

            Log.d(TAG, "Restoring backup from: ${tempFile.absolutePath}")
            val zipInputStream = ZipInputStream(FileInputStream(tempFile))
            val allSections = buildRestoreSections(candidate.writerGeneration)

            val restoreResult = zipInputStream.use { zis ->
                backupRepository.restoreBackup(zis, allSections, null)
            }

            val changesApplied = !restoreResult.result.isEmpty

            val restoreResultCommit = backupWebDavRestoreCoordinator.commitAutoRestore(
                restoredVersion = candidate.dataVersion,
                now = currentTime,
            )
            logBackupFlow(
                TAG,
                flow = BackupFlow.WEBDAV_AUTO_RESTORE,
                event = "restore_complete",
                reason = null,
                "changesApplied" to changesApplied,
                "version" to restoreResultCommit.restoredVersion,
                "legacyJarReposImported" to restoreResult.legacyJarReposImported,
                "legacyMigration" to isLegacyMigration,
            )

            if (isLegacyMigration) {
                settings.backupWebDavLastSeenLegacyCreatedAt = candidate.lastModified.time
                settings.isBackupWebDavAutoUploadBlockedByLegacyRestore = true
            }
            settings.hasCompletedBackupWebDavV2Migration = true

            if (isLegacyMigration) {
                logBackupFlow(
                    TAG,
                    flow = BackupFlow.WEBDAV_AUTO_RESTORE,
                    event = "post_restore_upload_skipped",
                    reason = "legacy_restore_block",
                )
            } else {
                kotlin.runCatching {
                    val out = File.createTempFile("webdav_backup_post_restore", ".bk.zip", this.cacheDir)
                    try {
                        java.util.zip.ZipOutputStream(out.outputStream()).use { zos ->
                            backupRepository.createBackup(zos, null)
                        }
                        val isSame = candidate.namespace == RemoteNamespace.V2 && areBackupsEqual(tempFile, out)
                        if (!isSame) {
                            val uploadResult = backupWebDavUploadCoordinator.uploadAndCommit(
                                file = out,
                                uploadKind = "auto",
                            )
                            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "post_restore_upload_complete", reason = null, "nextVersion" to uploadResult.targetVersion)
                        } else {
                            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "post_restore_upload_skipped", reason = "identical_content")
                        }
                    } finally {
                        out.delete()
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Post-restore comparison/upload failed", e)
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private suspend fun selectLegacyCandidate(): BackupFileInfo? {
        if (settings.hasCompletedBackupWebDavV2Migration) {
            return null
        }
        val legacyFiles = runCatching { webDavUploader.listBackupFiles(RemoteNamespace.V1) }
            .getOrElse { emptyList() }
        val candidate = selectPreferredCandidate(legacyFiles) ?: return null
        if (candidate.lastModified.time <= settings.backupWebDavLastSeenLegacyCreatedAt) {
            return null
        }
        return candidate
    }

    private fun selectPreferredCandidate(remoteFiles: List<BackupFileInfo>): BackupFileInfo? {
        return remoteFiles
            .sortedWith(
                compareByDescending<BackupFileInfo> { it.writerGeneration }
                    .thenByDescending { it.lastModified.time }
                    .thenByDescending { it.dataVersion ?: Int.MIN_VALUE },
            )
            .firstOrNull()
    }

    private fun buildRestoreSections(writerGeneration: Int): Set<BackupSection> {
        val baseSections = linkedSetOf(
            BackupSection.HISTORY,
            BackupSection.CATEGORIES,
            BackupSection.FAVOURITES,
            BackupSection.BOOKMARKS,
            BackupSection.SOURCES,
            BackupSection.EXTENSION_REPOS,
        )
        if (writerGeneration >= RemoteNamespace.V2.writerGeneration) {
            baseSections += BackupSection.ENTITY_GRAPH_ENTITIES
            baseSections += BackupSection.ENTITY_GRAPH_BINDINGS
            baseSections += BackupSection.ENTITY_GRAPH_RELATIONS
            baseSections += BackupSection.ENTITY_GRAPH_PREFS
        }
        return baseSections
    }

	companion object {
		private const val TAG = "WebDavAutoRestore"
		private const val NOTIFICATION_ID = 2001
		fun start(context: Context) {
			val intent = Intent(context, WebDavAutoRestoreService::class.java)
			ContextCompat.startForegroundService(context, intent)
		}
	}

    /**
     * 比较两个备份 zip 文件的实际内容是否相同。
     * 逐项读取每个条目（entry）内容并计算 SHA-256 摘要，按文件名比对。
     */
    private fun areBackupsEqual(fileA: File, fileB: File): Boolean {
        fun digestOfZip(file: File): Map<String, String> {
            val map = mutableMapOf<String, String>()
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 读取内容并计算摘要
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(8192)
                        var read: Int
                        while (true) {
                            read = zis.read(buf)
                            if (read <= 0) break
                            md.update(buf, 0, read)
                        }
                        map[entry.name] = md.digest().joinToString(separator = "") { b ->
                            val i = (b.toInt() and 0xFF)
                            i.toString(16).padStart(2, '0')
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return map
        }

        val a = digestOfZip(fileA)
        val b = digestOfZip(fileB)
        if (a.size != b.size) return false
        for ((name, hashA) in a) {
            val hashB = b[name] ?: return false
            if (hashA != hashB) return false
        }
        return true
    }
}
