package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.BuildConfig

@Serializable
class BackupIndex(
	@SerialName("app_id") val appId: String,
	@SerialName("app_version") val appVersion: Int,
	@SerialName("created_at") val createdAt: Long,
) {

	constructor() : this(
		appId = BuildConfig.APPLICATION_ID,
		appVersion = BuildConfig.VERSION_CODE,
		createdAt = System.currentTimeMillis(),
	)

	companion object {
		const val CURRENT_BACKUP_FORMAT_VERSION = 2
		const val CURRENT_SYNC_SCHEMA_VERSION = 2
		const val WRITER_GENERATION_V1 = 1
		const val WRITER_GENERATION_V2 = 2
	}
}
