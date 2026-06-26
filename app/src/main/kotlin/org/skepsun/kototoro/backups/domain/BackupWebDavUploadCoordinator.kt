package org.skepsun.kototoro.backups.domain

import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupWebDavUploadCoordinator @Inject constructor(
	private val settings: AppSettings,
	private val webDavBackupUploader: WebDavBackupUploader,
) {

	data class UploadCommitResult(
		val uploadedAt: Long,
		val targetVersion: Int,
		val uploadKind: String,
	)

	suspend fun uploadAndCommit(
		file: File,
		uploadKind: String,
		now: Long = System.currentTimeMillis(),
	): UploadCommitResult {
		BackupPayloadGuard.requireRestorableWorkSnapshot(
			file = file,
			operation = "WebDAV $uploadKind upload",
		)
		val targetVersion = settings.backupWebDavDataVersion + 1
		webDavBackupUploader.uploadBackup(
			file = file,
			targetVersion = targetVersion,
			namespace = RemoteNamespace.V3,
		)
		settings.backupWebDavLastUploadTime = now
		settings.backupWebDavLastUploadKind = uploadKind
		settings.backupWebDavDataVersion = targetVersion
		settings.backupWebDavWriterGeneration = RemoteNamespace.V3.writerGeneration
		settings.backupWebDavLastAuthoritativeSemanticSchemaVersion = BackupIndex.CURRENT_SYNC_SCHEMA_VERSION
		settings.isWorkMigrationSyncWriteBlocked = false
		settings.requiresWorkMigrationNormalization = false
		settings.hasCompletedBackupWebDavV2Migration = true
		settings.isBackupWebDavAutoUploadBlockedByLegacyRestore = false
		return UploadCommitResult(
			uploadedAt = now,
			targetVersion = targetVersion,
			uploadKind = uploadKind,
		)
	}
}
