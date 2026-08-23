package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

/**
 * Serialized form of a [SourceOriginEntity] inside the optional `SOURCE_ORIGINS` backup
 * section (plan §8.2, backup schema 4).
 *
 * All fields are optional except [sourceKey] so legacy/3rd-party payloads never force
 * guessing; absent origins are materialized from installed catalog / referenced works with
 * only the strictly derivable fields set.
 */
@Serializable
data class SourceOriginBackup(
    @SerialName("source_key") val sourceKey: String,
    @SerialName("kind") val kind: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("repository_url") val repositoryUrl: String? = null,
    @SerialName("repository_name") val repositoryName: String? = null,
    @SerialName("locator") val locator: String? = null,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("signing_digest") val signingDigest: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
) {

    fun toEntity(): SourceOriginEntity = SourceOriginEntity(
        sourceKey = sourceKey,
        kind = kind,
        displayName = displayName,
        contentType = contentType,
        packageName = packageName,
        sourceId = sourceId,
        repositoryUrl = repositoryUrl,
        repositoryName = repositoryName,
        locator = locator,
        versionName = versionName,
        versionCode = versionCode,
        signingDigest = signingDigest,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromEntity(entity: SourceOriginEntity): SourceOriginBackup = SourceOriginBackup(
            sourceKey = entity.sourceKey,
            kind = entity.kind,
            displayName = entity.displayName,
            contentType = entity.contentType,
            packageName = entity.packageName,
            sourceId = entity.sourceId,
            repositoryUrl = entity.repositoryUrl,
            repositoryName = entity.repositoryName,
            locator = entity.locator,
            versionName = entity.versionName,
            versionCode = entity.versionCode,
            signingDigest = entity.signingDigest,
            lastSeenAt = entity.lastSeenAt,
            updatedAt = entity.updatedAt,
        )
    }
}
