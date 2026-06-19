package org.skepsun.kototoro.backups.ui.webdav

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavAutoRestoreRunner @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: AppSettings,
    private val backupRepository: BackupRepository,
    private val webDavUploader: WebDavBackupUploader,
    private val backupWebDavRestoreCoordinator: BackupWebDavRestoreCoordinator,
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
            val v2Files = allRemoteFiles.filter { it.namespace == RemoteNamespace.V2 }
            val v1Files = allRemoteFiles.filter { it.namespace == RemoteNamespace.V1 }

            val candidate = when {
                v3Files.isNotEmpty() -> selectPreferredCandidate(v3Files)
                v2Files.isNotEmpty() -> selectPreferredCandidate(v2Files)
                else -> selectLegacyCandidate(v1Files)
            } ?: run {
                val reason = if (allRemoteFiles.isEmpty()) "no_remote_backups" else "no_compatible_backup"
                logBackupFlow(TAG, flow = BackupFlow.WEBDAV_AUTO_RESTORE, event = "restore_skipped", reason = reason)
                settings.backupWebDavLastAutoRestoreCheckTime = currentTime
                return
            }

            restoreCandidate(
                candidate = candidate,
                currentTime = currentTime,
                isLegacyMigration = candidate.namespace == RemoteNamespace.V1,
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

        val tempFile = File.createTempFile("webdav_backup", ".bk.zip", appContext.cacheDir)
        try {
            Log.d(TAG, "Downloading backup file: ${candidate.name}")
            webDavUploader.downloadBackup(candidate.name, tempFile, candidate.namespace)

            Log.d(TAG, "Restoring backup from: ${tempFile.absolutePath}")
            val restoreResult = ZipInputStream(FileInputStream(tempFile)).use { zis ->
                backupRepository.restoreBackup(
                    input = zis,
                    sections = buildRestoreSections(candidate.writerGeneration),
                    progress = null,
                    restoreMode = BackupRepository.RestoreMode.SNAPSHOT_REPLACE,
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
                "legacyMigration" to isLegacyMigration,
            )

            if (isLegacyMigration) {
                settings.backupWebDavLastSeenLegacyCreatedAt = candidate.lastModified.time
                settings.isBackupWebDavAutoUploadBlockedByLegacyRestore = true
            }
            settings.hasCompletedBackupWebDavV2Migration = true
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun selectLegacyCandidate(legacyFiles: List<BackupFileInfo>): BackupFileInfo? {
        if (settings.hasCompletedBackupWebDavV2Migration) {
            return null
        }
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
            BackupSection.INDEX,
            BackupSection.HISTORY,
            BackupSection.CATEGORIES,
            BackupSection.FAVOURITES,
            BackupSection.BOOKMARKS,
            BackupSection.STATS,
            BackupSection.EXTENSION_REPOS,
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
