package org.skepsun.kototoro.backups.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupFlowPolicy @Inject constructor(
	private val settings: AppSettings,
) {

	data class FlowDecision(
		val allowed: Boolean,
		val reason: String? = null,
	)

	data class PeriodicalBackupDestinations(
		val hasLocalCopyDestination: Boolean,
		val hasTelegramDestination: Boolean,
		val hasWebDavDestination: Boolean,
	) {
		val hasAnyDestination: Boolean
			get() = hasLocalCopyDestination || hasTelegramDestination || hasWebDavDestination
	}

	data class PeriodicalBackupPlan(
		val decision: FlowDecision,
		val destinations: PeriodicalBackupDestinations,
	)

	fun autoRestoreStartupDecision(): FlowDecision {
		return when {
			!settings.isBackupWebDavAutoRestoreEnabled -> FlowDecision(false, "feature_disabled")
			!hasCompleteWebDavConfig() -> FlowDecision(false, "incomplete_config")
			else -> FlowDecision(true)
		}
	}

	fun autoSyncUploadDecision(): FlowDecision {
		return when {
			!settings.isBackupWebDavAutoSyncEnabled || !settings.isBackupWebDavUploadEnabled ->
				FlowDecision(false, "feature_disabled")
			settings.isBackupWebDavAutoUploadBlockedByLegacyRestore ->
				FlowDecision(false, "legacy_restore_block")
			settings.isWorkMigrationSyncWriteBlocked ->
				FlowDecision(false, "work_migration_write_block")
			!hasCompleteWebDavConfig() -> FlowDecision(false, "incomplete_config")
			else -> FlowDecision(true)
		}
	}

	fun periodicalBackupPlan(telegramAvailable: Boolean): PeriodicalBackupPlan {
		val destinations = PeriodicalBackupDestinations(
			hasLocalCopyDestination = settings.isBackupWebDavKeepLocalCopyEnabled &&
				settings.periodicalBackupDirectory != null,
			hasTelegramDestination = settings.isBackupTelegramUploadEnabled && telegramAvailable,
			hasWebDavDestination = settings.isBackupWebDavUploadEnabled,
		)
		val decision = when {
			!settings.isPeriodicalBackupEnabled -> FlowDecision(false, "feature_disabled")
			!destinations.hasAnyDestination -> FlowDecision(false, "no_destination")
			else -> FlowDecision(true)
		}
		return PeriodicalBackupPlan(
			decision = decision,
			destinations = destinations,
		)
	}

	fun periodicalBackupFrequencyDecision(
		now: Long,
		localLastBackupTime: Long?,
		webDavLastUploadTime: Long,
		destinations: PeriodicalBackupDestinations,
	): FlowDecision {
		val effectiveLast = listOfNotNull(
			localLastBackupTime.takeIf { destinations.hasLocalCopyDestination },
			webDavLastUploadTime.takeIf { destinations.hasWebDavDestination && it > 0L },
		).maxOrNull() ?: return FlowDecision(true)
		return if (effectiveLast + settings.periodicalBackupFrequencyMillis > now) {
			FlowDecision(false, "frequency_gate")
		} else {
			FlowDecision(true)
		}
	}

	private fun hasCompleteWebDavConfig(): Boolean {
		return !settings.backupWebDavServerUrl.isNullOrBlank() &&
			!settings.backupWebDavUsername.isNullOrBlank() &&
			!settings.backupWebDavPassword.isNullOrBlank()
	}
}
