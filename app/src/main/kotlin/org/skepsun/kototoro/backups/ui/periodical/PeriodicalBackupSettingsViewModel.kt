package org.skepsun.kototoro.backups.ui.periodical

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.resolveFile
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class PeriodicalBackupSettingsViewModel @Inject constructor(
	private val settings: AppSettings,
	private val telegramUploader: TelegramBackupUploader,
	private val webDavUploader: WebDavBackupUploader,
	private val backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator,
	private val backupWebDavRestoreCoordinator: BackupWebDavRestoreCoordinator,
	private val backupStorage: ExternalBackupStorage,
	private val repository: org.skepsun.kototoro.backups.data.BackupRepository,
	@ApplicationContext private val appContext: Context,
) : BaseViewModel() {

	val isTelegramAvailable
		get() = telegramUploader.isAvailable

	val lastBackupDate = MutableStateFlow<Date?>(null)
	val backupsDirectory = MutableStateFlow<String?>("")
	val isTelegramCheckLoading = MutableStateFlow(false)
	val isWebDavCheckLoading = MutableStateFlow(false)
	val webDavUploadBusyMessageRes = MutableStateFlow<Int?>(null)
	val webDavRestoreBusyMessageRes = MutableStateFlow<Int?>(null)
	val onActionDone = MutableEventFlow<ReversibleAction>()

	// 最近一次 WebDAV 操作（类型文案资源ID，发生时间毫秒）
	val webDavLastAction = MutableStateFlow<Pair<Int, Long>?>(null)

	init {
		updateSummaryData()
	}

	fun checkTelegram() {
		launchJob(Dispatchers.Default) {
			try {
				isTelegramCheckLoading.value = true
				telegramUploader.sendTestMessage()
				onActionDone.call(ReversibleAction(R.string.connection_ok, null))
			} finally {
				isTelegramCheckLoading.value = false
			}
		}
	}

	fun checkWebDav() {
		launchJob(Dispatchers.Default) {
			try {
				isWebDavCheckLoading.value = true
				webDavUploadBusyMessageRes.value = R.string.webdav_connection_check_in_progress
				webDavUploader.sendTestConnection()
				onActionDone.call(ReversibleAction(R.string.connection_ok, null))
			} finally {
				isWebDavCheckLoading.value = false
				webDavUploadBusyMessageRes.value = null
			}
		}
	}

	fun uploadWebDavNow() {
		launchJob(Dispatchers.Default) {
			val output = org.skepsun.kototoro.backups.domain.BackupUtils.createTempFile(appContext)
			try {
				webDavUploadBusyMessageRes.value = R.string.webdav_upload_in_progress
				java.util.zip.ZipOutputStream(output.outputStream()).use {
					repository.createBackup(it, null)
				}
				// 根据设置决定是否保留本地副本
				var localCopyFailed = false
				if (settings.isBackupWebDavKeepLocalCopyEnabled) {
					// 本地副本失败不应阻断 WebDAV 上传
					runCatching {
						backupStorage.put(output)
						backupStorage.trim(settings.periodicalBackupMaxCount)
					}.onFailure { localCopyFailed = true }
				}
				backupWebDavUploadCoordinator.uploadAndCommit(
					file = output,
					uploadKind = "manual",
				)
				onActionDone.call(
					ReversibleAction(
						if (localCopyFailed) R.string.webdav_upload_success_local_copy_failed
						else R.string.webdav_upload_success,
						null,
					),
				)
				// 仅在保留本地副本时，更新上次本地备份时间展示
				if (settings.isBackupWebDavKeepLocalCopyEnabled) {
					updateLastBackupDate()
				}
				updateWebDavLastAction()
			} catch (e: Exception) {
				errorEvent.call(e)
			} finally {
				webDavUploadBusyMessageRes.value = null
				output.delete()
			}
		}
	}

	fun restoreWebDavNow() {
		launchJob(Dispatchers.Default) {
			try {
				webDavRestoreBusyMessageRes.value = R.string.webdav_restore_in_progress
				val latest = webDavUploader.getLatestBackup(RemoteNamespace.V3)
					?: webDavUploader.getLatestBackup(RemoteNamespace.V2)
					?: webDavUploader.getLatestBackup(RemoteNamespace.V1)
				if (latest == null) {
					throw IllegalStateException("No WebDAV backups found")
				}
				val tempFile = java.io.File.createTempFile("webdav_backup_manual", ".bk.zip", appContext.cacheDir)
				try {
					webDavUploader.downloadBackup(latest.name, tempFile, latest.namespace)
					val allSections = setOf(
						org.skepsun.kototoro.backups.domain.BackupSection.INDEX,
						org.skepsun.kototoro.backups.domain.BackupSection.HISTORY,
						org.skepsun.kototoro.backups.domain.BackupSection.CATEGORIES,
						org.skepsun.kototoro.backups.domain.BackupSection.FAVOURITES,
						org.skepsun.kototoro.backups.domain.BackupSection.BOOKMARKS,
						org.skepsun.kototoro.backups.domain.BackupSection.STATS,
						org.skepsun.kototoro.backups.domain.BackupSection.WORK_HISTORY,
						org.skepsun.kototoro.backups.domain.BackupSection.WORK_FAVOURITES,
						org.skepsun.kototoro.backups.domain.BackupSection.WORK_STATS,
						org.skepsun.kototoro.backups.domain.BackupSection.SOURCES,
						org.skepsun.kototoro.backups.domain.BackupSection.EXTENSION_REPOS,
						org.skepsun.kototoro.backups.domain.BackupSection.SETTINGS,
						org.skepsun.kototoro.backups.domain.BackupSection.SETTINGS_READER_GRID,
						org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_ENTITIES,
						org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_BINDINGS,
						org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_RELATIONS,
						org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_PREFS,
                    )
                    val restoreResult = java.util.zip.ZipInputStream(java.io.FileInputStream(tempFile)).use { zis ->
                        repository.restoreBackup(
                            input = zis,
                            sections = allSections,
                            progress = null,
                            restoreMode = BackupRepository.RestoreMode.SNAPSHOT_REPLACE,
                        )
                    }
					val restoreContext = repository.resolveRestoreSemanticContext(restoreResult.backupIndex)
					backupWebDavRestoreCoordinator.commitManualRestore(
						state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
							semanticSchemaVersion = restoreContext.semanticSchemaVersion,
							transportGeneration = restoreContext.transportGeneration,
						),
					)
					onActionDone.call(
						ReversibleAction(
							if (restoreContext.isLegacySemanticSchema && restoreResult.legacyJarReposImported) {
								R.string.webdav_restore_success_legacy_requires_normalization_with_jar_hint
							} else if (restoreContext.isLegacySemanticSchema) {
								R.string.webdav_restore_success_legacy_requires_normalization
							} else if (restoreResult.legacyJarReposImported) {
								R.string.webdav_restore_success_legacy_jar_hint
							} else {
								R.string.webdav_restore_success
							},
							null,
						),
					)
					updateWebDavLastAction()
				} finally {
					if (tempFile.exists()) tempFile.delete()
				}
			} catch (e: Exception) {
				errorEvent.call(e)
			} finally {
				webDavRestoreBusyMessageRes.value = null
			}
		}
	}

	fun updateSummaryData() {
		updateBackupsDirectory()
		updateLastBackupDate()
		updateWebDavLastAction()
	}

	private fun updateBackupsDirectory() = launchJob(Dispatchers.Default) {
		val dir = settings.periodicalBackupDirectory
		backupsDirectory.value = if (dir != null) {
			dir.toUserFriendlyString()
		} else {
			BackupUtils.getAppBackupDir(appContext).path
		}
	}

	private fun updateLastBackupDate() = launchJob(Dispatchers.Default) {
		lastBackupDate.value = backupStorage.getLastBackupDate()
	}

	private fun updateWebDavLastAction() = launchJob(Dispatchers.Default) {
		val upload = settings.backupWebDavLastUploadTime
		val autoRestore = settings.backupWebDavLastRestoreTime
		val manualRestore = settings.backupWebDavLastManualRestoreTime
		val max = listOf(upload, autoRestore, manualRestore).maxOrNull() ?: 0L
		val label = when (max) {
			upload -> when (settings.backupWebDavLastUploadKind) {
				"manual" -> R.string.action_manual_upload
				else -> R.string.action_auto_upload
			}
			autoRestore -> R.string.action_auto_restore
			manualRestore -> R.string.action_manual_restore
			else -> null
		}
		webDavLastAction.value = label?.let { it to max }
	}

	private fun Uri.toUserFriendlyString(): String? {
		val df = DocumentFile.fromTreeUri(appContext, this)
		if (df?.canWrite() != true) {
			return null
		}
		return resolveFile(appContext)?.path ?: toString()
	}
}
