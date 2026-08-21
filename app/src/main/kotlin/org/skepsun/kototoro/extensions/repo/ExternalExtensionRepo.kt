package org.skepsun.kototoro.extensions.repo

data class ExternalExtensionRepo(
    val type: ExternalExtensionType,
    val baseUrl: String,
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSuccessAt: Long,
    val lastError: String?,
    val version: String? = null,
) {
    val displayName: String
        get() = shortName ?: name
}
