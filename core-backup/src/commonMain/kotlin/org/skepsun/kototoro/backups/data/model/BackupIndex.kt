package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    companion object {
        const val CURRENT_BACKUP_FORMAT_VERSION = 2
        const val CURRENT_SYNC_SCHEMA_VERSION = 4
        const val WRITER_GENERATION_V1 = 1
        const val WRITER_GENERATION_V2 = 2
        const val WRITER_GENERATION_V3 = 3

        /**
         * Historical writers introduced work sections in schema 3; schema 4 added SOURCE_ORIGINS.
         * Keep the boundary independent of the latest schema version when classifying old archives.
         * Readers now project legacy work state onto manga_id without recreating entity/work ownership.
         */
        const val LEGACY_SEMANTIC_SCHEMA_BOUNDARY = 3
    }
}
