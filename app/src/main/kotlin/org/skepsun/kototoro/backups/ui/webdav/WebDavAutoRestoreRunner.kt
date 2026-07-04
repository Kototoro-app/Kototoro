package org.skepsun.kototoro.backups.ui.webdav

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupPayloadGuard
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.ui.periodical.BackupFileInfo
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.BackupFlow
import org.skepsun.kototoro.core.util.logBackupFlow
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavAutoRestoreRunner @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: AppSettings,
    private val backupRepository: BackupRepository,
    private val webDavUploader: WebDavBackupUploader,
    private val backupWebDavRestoreCoordinator: BackupWebDavRestoreCoordinator,
    private val backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator,
) {

    suspend fun run() {
        val currentTime = System.currentTimeMillis()
        if (isAlreadyCheckedToday(currentTime)) {
            logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "start_skipped", reason = "already_checked_today")
            return
        }

        logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_check_started")

        try {
            Log.d(TAG, "performAutoRestore: listing backups once...")
            val allRemoteFiles = webDavUploader.listAllBackupFiles()
            val v3Files = allRemoteFiles.filter { it.namespace == RemoteNamespace.V3 }

            val candidate = selectPreferredCandidate(v3Files) ?: run {
                val reason = if (allRemoteFiles.isEmpty()) "no_remote_backups" else "no_compatible_backup"
                logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_skipped", reason = reason)
                settings.backupWebDavLastAutoRestoreCheckTime = currentTime
                return
            }

            restoreCandidate(
                candidate = candidate,
                currentTime = currentTime,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform auto restore", e)
            throw e
        }

        settings.backupWebDavLastAutoRestoreCheckTime = currentTime
    }

    private suspend fun restoreCandidate(
        candidate: BackupFileInfo,
        currentTime: Long,
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

        val tempFile = File.createTempFile("webdav_backup", ".bk.zip", appContext.cacheDir)
        try {
            Log.d(TAG, "Downloading backup file: ${candidate.name}")
            webDavUploader.downloadBackup(candidate.name, tempFile, candidate.namespace)
            val inspection = BackupPayloadGuard.requireRestorableWorkSnapshot(
                file = tempFile,
                operation = "auto WebDAV restore",
            )
            Log.d(
                TAG,
                "Auto restore backup payload: size=${tempFile.length()}b entries=${inspection.describe()}",
            )

            Log.d(TAG, "Restoring backup from: ${tempFile.absolutePath}")
            val restoreResult = ZipInputStream(FileInputStream(tempFile)).use { zis ->
                backupRepository.restoreBackup(
                    input = zis,
                    sections = buildRestoreSections(candidate.writerGeneration),
                    progress = null,
                    restoreMode = BackupRepository.RestoreMode.MERGE,
                )
            }
            val restoreContext = backupRepository.resolveRestoreSemanticContext(restoreResult.backupIndex)
            val changesApplied = !restoreResult.result.isEmpty

            val restoreResultCommit = backupWebDavRestoreCoordinator.commitAutoRestore(
                restoredVersion = candidate.dataVersion,
                state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
                    semanticSchemaVersion = restoreContext.semanticSchemaVersion,
                    transportGeneration = restoreContext.transportGeneration,
                ),
                now = currentTime,
            )
            logBackupFlow(
                TAG,
                flow = BackupFlow.WEBDAV_AUTO_RESTORE,
                event = "restore_complete",
                reason = null,
                "changesApplied" to changesApplied,
                "version" to restoreResultCommit.restoredVersion,
                "semanticSchemaVersion" to restoreResultCommit.semanticSchemaVersion,
                "transportGeneration" to restoreResultCommit.transportGeneration,
                "writeBlocked" to restoreResultCommit.writeBlocked,
                "legacyJarReposImported" to restoreResult.legacyJarReposImported,
                "legacyMigration" to false,
            )

            if (restoreContext.isAuthoritativeWorkSchema && !restoreResultCommit.writeBlocked) {
                uploadMergedSnapshot(currentTime)
            } else {
                logBackupFlow(
                    TAG,
                    flow = BackupFlow.WEBDAV_AUTO_RESTORE,
                    event = "merged_snapshot_upload_skipped",
                    reason = "non_authoritative_restore",
                    "semanticSchemaVersion" to restoreResultCommit.semanticSchemaVersion,
                    "transportGeneration" to restoreResultCommit.transportGeneration,
                    "writeBlocked" to restoreResultCommit.writeBlocked,
                )
            }
            settings.hasCompletedBackupWebDavV2Migration = true
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private suspend fun uploadMergedSnapshot(currentTime: Long) {
        val output = BackupUtils.createTempFile(appContext)
        try {
            ZipOutputStream(output.outputStream()).use { zip ->
                backupRepository.createBackup(zip, null)
            }
            val uploadResult = backupWebDavUploadCoordinator.uploadAndCommit(
                file = output,
                uploadKind = "auto_restore_merge",
                now = currentTime,
            )
            logBackupFlow(
                TAG,
                flow = BackupFlow.WEBDAV_AUTO_RESTORE,
                event = "merged_snapshot_uploaded",
                reason = null,
                "nextVersion" to uploadResult.targetVersion,
            )
        } finally {
            output.delete()
        }
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
            BackupSection.INDEX,
            BackupSection.HISTORY,
            BackupSection.CATEGORIES,
            BackupSection.FAVOURITES,
            BackupSection.BOOKMARKS,
            BackupSection.STATS,
            BackupSection.EXTENSION_REPOS,
            BackupSection.TRACKS,
            BackupSection.TRACK_LOGS,
        )
        if (writerGeneration >= RemoteNamespace.V2.writerGeneration) {
            baseSections += BackupSection.ENTITY_GRAPH_ENTITIES
            baseSections += BackupSection.ENTITY_GRAPH_BINDINGS
            baseSections += BackupSection.ENTITY_GRAPH_RELATIONS
            baseSections += BackupSection.ENTITY_GRAPH_PREFS
        }
        if (writerGeneration >= RemoteNamespace.V3.writerGeneration) {
            baseSections += BackupSection.WORK_HISTORY
            baseSections += BackupSection.WORK_FAVOURITES
            baseSections += BackupSection.WORK_STATS
            baseSections += BackupSection.SETTINGS
            baseSections += BackupSection.SETTINGS_READER_GRID
        }
        return baseSections
    }

    private fun isAlreadyCheckedToday(now: Long): Boolean {
        val lastCheck = settings.backupWebDavLastAutoRestoreCheckTime
        if (lastCheck <= 0L) {
            return false
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dayFormat.format(Date(lastCheck)) == dayFormat.format(Date(now))
    }

    private companion object {
        private const val TAG = "WebDavAutoRestore"
    }
}
