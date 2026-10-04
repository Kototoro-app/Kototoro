package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

@Serializable
class ExtensionRepoBackup(
    @SerialName("type") val type: ExternalExtensionType,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("name") val name: String,
    @SerialName("short_name") val shortName: String?,
    @SerialName("website") val website: String,
    @SerialName("signing_key_fingerprint") val signingKeyFingerprint: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("last_success_at") val lastSuccessAt: Long,
    @SerialName("last_error") val lastError: String?,
    @SerialName("version") val version: String?,
) {

    constructor(entity: ExternalExtensionRepoEntity) : this(
        type = entity.type,
        baseUrl = entity.baseUrl,
        name = entity.name,
        shortName = entity.shortName,
        website = entity.website,
        signingKeyFingerprint = entity.signingKeyFingerprint,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastSuccessAt = entity.lastSuccessAt,
        lastError = entity.lastError,
        version = entity.version,
    )

    fun toEntity() = ExternalExtensionRepoEntity(
        type = type,
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        version = version,
    )
}
