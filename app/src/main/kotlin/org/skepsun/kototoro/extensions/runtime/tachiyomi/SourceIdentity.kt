package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * The set of Tachiyomi-ABI source ecosystems that keys sources by a numeric upstream source ID
 * and expose a `PREFIX_{sourceId}` source key inside Kototoro.
 *
 * JAR and CLOUDSTREAM are not ID-keyed Tachiyomi ecosystems and are deliberately absent.
 */
val ExternalExtensionType.tachiyomiSourcePrefix: String?
    get() = when (this) {
        ExternalExtensionType.MIHON -> "MIHON_"
        ExternalExtensionType.ANIYOMI -> "ANIYOMI_"
        ExternalExtensionType.IREADER -> "IREADER_"
        ExternalExtensionType.TSUNDOKU -> "TSUNDOKU_"
        ExternalExtensionType.JAR,
        ExternalExtensionType.CLOUDSTREAM -> null
    }

/**
 * Strict source identity: the exact combination of ecosystem and upstream source ID.
 *
 * Display name, package name, repository URL and language are **never** part of identity;
 * they cannot be used to correlate sources across ecosystems.
 */
data class SourceIdentity(
    val ecosystem: ExternalExtensionType,
    val sourceId: Long,
) {
    /**
     * Kototoro-visible source key, e.g. `MIHON_123` or `TSUNDOKU_9001`.
     */
    val sourceKey: String
        get() = requireNotNull(ecosystem.tachiyomiSourcePrefix) {
            "ecosystem $ecosystem has no numeric source-key prefix"
        } + sourceId

    companion object {
        /**
         * Parses a strict identity from a source key such as `TSUNDOKU_9001`.
         * Returns null for keys that match no known Tachiyomi prefix or have a non-numeric id.
         */
        fun fromSourceKey(sourceKey: String): SourceIdentity? {
            for (ecosystem in ExternalExtensionType.values()) {
                val prefix = ecosystem.tachiyomiSourcePrefix ?: continue
                if (sourceKey.startsWith(prefix)) {
                    val id = sourceKey.removePrefix(prefix).toLongOrNull() ?: return null
                    return SourceIdentity(ecosystem, id)
                }
            }
            return null
        }
    }
}
