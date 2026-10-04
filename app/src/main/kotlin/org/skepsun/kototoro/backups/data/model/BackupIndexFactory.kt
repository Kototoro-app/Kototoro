package org.skepsun.kototoro.backups.data.model

import org.skepsun.kototoro.BuildConfig

/**
 * Index of a backup written by this build. Lives in :app because the application id and version code come from
 * [BuildConfig]; the data class itself is shared with other platforms through :core-backup.
 */
fun BackupIndex(
    deviceId: String = "",
    dataVersion: Int = 0,
    exportedAt: Long = System.currentTimeMillis(),
): BackupIndex = BackupIndex(
    appId = BuildConfig.APPLICATION_ID,
    appVersion = BuildConfig.VERSION_CODE,
    transportGeneration = BackupIndex.WRITER_GENERATION_V3,
    semanticSchemaVersion = BackupIndex.CURRENT_SYNC_SCHEMA_VERSION,
    deviceId = deviceId,
    dataVersion = dataVersion,
    createdAt = exportedAt,
    exportedAt = exportedAt,
)

fun BackupIndex.Companion.forKotatsuCompatibility(exportedAt: Long): BackupIndex = BackupIndex(
    appId = BuildConfig.APPLICATION_ID,
    appVersion = BuildConfig.VERSION_CODE,
    transportGeneration = BackupIndex.WRITER_GENERATION_V1,
    semanticSchemaVersion = 1,
    deviceId = "",
    dataVersion = 0,
    createdAt = exportedAt,
    exportedAt = exportedAt,
)
