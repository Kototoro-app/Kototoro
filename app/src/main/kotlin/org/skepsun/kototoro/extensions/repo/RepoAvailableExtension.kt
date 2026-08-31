package org.skepsun.kototoro.extensions.repo

data class RepoAvailableExtension(
    val type: ExternalExtensionType,
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double,
    val lang: String,
    /** Concrete languages declared for sources inside this package. */
    val languageCodes: Set<String> = emptySet(),
    /** Whether the package contains sources explicitly marked as universal/multilingual. */
    val includesUniversalLanguage: Boolean = false,
    /** Distinguishes known universal coverage from missing or failed metadata discovery. */
    val isLanguageMetadataKnown: Boolean = languageCodes.isNotEmpty() || includesUniversalLanguage,
    val isNsfw: Boolean,
    val sourceNames: List<String>,
    /** Numeric source ids declared by the index, when the repo format exposes them. */
    val sourceIds: List<Long> = emptyList(),
    val archiveName: String,
    val archiveUrl: String? = null,
    val iconUrl: String,
    val repoUrl: String,
    val repoName: String,
    val signatureHash: String,
    val isCompatible: Boolean,
)
