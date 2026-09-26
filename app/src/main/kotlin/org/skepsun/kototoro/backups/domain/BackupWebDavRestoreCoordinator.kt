package org.skepsun.kototoro.backups.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupWebDavRestoreCoordinator @Inject constructor(
    private val settings: AppSettings,
) {

    data class RestoreSemanticState(
        val semanticSchemaVersion: Int,
        val transportGeneration: Int,
    )

    data class RestoreCommitResult(
        val restoredAt: Long,
        val restoredVersion: Int?,
        val effectiveDataVersion: Int,
        val restoreKind: String,
        val semanticSchemaVersion: Int,
        val transportGeneration: Int,
    )

    fun commitAutoRestore(
        restoredVersion: Int?,
        state: RestoreSemanticState,
        now: Long = System.currentTimeMillis(),
    ): RestoreCommitResult {
        settings.backupWebDavLastRestoreTime = now
        val effectiveDataVersion = mergeRestoredVersion(restoredVersion)
        applySemanticRestoreState(state)
        return RestoreCommitResult(
            restoredAt = now,
            restoredVersion = restoredVersion,
            effectiveDataVersion = effectiveDataVersion,
            restoreKind = "auto",
            semanticSchemaVersion = state.semanticSchemaVersion,
            transportGeneration = state.transportGeneration,
        )
    }

    fun commitManualRestore(
        state: RestoreSemanticState,
        now: Long = System.currentTimeMillis(),
    ): RestoreCommitResult {
        settings.backupWebDavLastManualRestoreTime = now
        applySemanticRestoreState(state)
        return RestoreCommitResult(
            restoredAt = now,
            restoredVersion = null,
            effectiveDataVersion = settings.backupWebDavDataVersion,
            restoreKind = "manual",
            semanticSchemaVersion = state.semanticSchemaVersion,
            transportGeneration = state.transportGeneration,
        )
    }

    private fun mergeRestoredVersion(restoredVersion: Int?): Int {
        val currentVersion = settings.backupWebDavDataVersion
        if (restoredVersion != null && restoredVersion > currentVersion) {
            settings.backupWebDavDataVersion = restoredVersion
            return restoredVersion
        }
        return currentVersion
    }

    private fun applySemanticRestoreState(state: RestoreSemanticState) {
        val normalizedSemanticVersion = state.semanticSchemaVersion.coerceAtLeast(1)
        val normalizedTransportGeneration = state.transportGeneration.coerceAtLeast(1)
        settings.backupWebDavLastImportedSemanticSchemaVersion = normalizedSemanticVersion
        settings.backupWebDavWriterGeneration = maxOf(
            settings.backupWebDavWriterGeneration,
            normalizedTransportGeneration,
        )

        if (normalizedTransportGeneration >= 3 && normalizedSemanticVersion >= 3) {
            settings.backupWebDavLastAuthoritativeSemanticSchemaVersion = normalizedSemanticVersion
        }
    }
}
