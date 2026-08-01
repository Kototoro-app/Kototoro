package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.BuildConfig

@Serializable
class BackupIndex(
	@SerialName("app_id") val appId: String,
	@SerialName("app_version") val appVersion: Int,
	@SerialName("transport_generation") val transportGeneration: Int = WRITER_GENERATION_V1,
	@SerialName("semantic_schema_version") val semanticSchemaVersion: Int = 1,
	@SerialName("device_id") val deviceId: String = "",
	@SerialName("data_version") val dataVersion: Int = 0,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("exported_at") val exportedAt: Long = createdAt,
) {

	constructor(
		deviceId: String = "",
		dataVersion: Int = 0,
		exportedAt: Long = System.currentTimeMillis(),
	) : this(
		appId = BuildConfig.APPLICATION_ID,
		appVersion = BuildConfig.VERSION_CODE,
		transportGeneration = WRITER_GENERATION_V3,
		semanticSchemaVersion = CURRENT_SYNC_SCHEMA_VERSION,
		deviceId = deviceId,
		dataVersion = dataVersion,
		createdAt = exportedAt,
		exportedAt = exportedAt,
	)

	companion object {
		const val CURRENT_BACKUP_FORMAT_VERSION = 2
		const val CURRENT_SYNC_SCHEMA_VERSION = 3
		const val WRITER_GENERATION_V1 = 1
		const val WRITER_GENERATION_V2 = 2
		const val WRITER_GENERATION_V3 = 3

		fun forKotatsuCompatibility(exportedAt: Long): BackupIndex = BackupIndex(
			appId = BuildConfig.APPLICATION_ID,
			appVersion = BuildConfig.VERSION_CODE,
			transportGeneration = WRITER_GENERATION_V1,
			semanticSchemaVersion = 1,
			deviceId = "",
			dataVersion = 0,
			createdAt = exportedAt,
			exportedAt = exportedAt,
		)
	}
}
