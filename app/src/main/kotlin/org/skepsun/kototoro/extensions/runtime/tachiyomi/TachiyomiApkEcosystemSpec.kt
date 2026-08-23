package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

/**
 * Static declaration of one Tachiyomi-ABI extension ecosystem: the manifest feature, metadata
 * keys, identity prefix and exact set of accepted extension-lib versions.
 *
 * A spec is the single source of truth for how a scanned APK is classified and versioned.
 * Mihon keeps its historical loose identification rules; Tsundoku uses strict rules and only
 * accepts extensions-lib 1.4 / 1.6.
 */
data class TachiyomiApkEcosystemSpec(
    val extensionType: ExternalExtensionType,
    /** Directory under the app's private storage where managed local APKs live. */
    val ecosystemDir: String,
    /** Source-key prefix, e.g. "MIHON_" / "TSUNDOKU_". */
    val sourcePrefix: String,
    /** Android manifest <uses-feature> that marks an APK as belonging to this ecosystem. */
    val requiredFeature: String,
    /** Manifest metadata key carrying the direct source class. */
    val sourceMetadataKey: String,
    /** Manifest metadata key carrying the SourceFactory class (nullable). */
    val factoryMetadataKey: String?,
    /** Exact accepted extensions-lib versions (e.g. "1.4", "1.6"). */
    val acceptedLibVersions: Set<String>,
    /** Strict ecosystems require the required feature; Mihon keeps loose name/metadata fallback. */
    val strictIdentification: Boolean,
)

object TachiyomiApkEcosystemSpecs {

    val MIHON = TachiyomiApkEcosystemSpec(
        extensionType = ExternalExtensionType.MIHON,
        ecosystemDir = "mihon",
        sourcePrefix = "MIHON_",
        requiredFeature = "tachiyomi.extension",
        sourceMetadataKey = "tachiyomi.extension.class",
        factoryMetadataKey = "tachiyomi.extension.factory",
        acceptedLibVersions = (12..19).map { it / 10.0 }.map(::formatLibVersion).toSet(),
        strictIdentification = false,
    )

    val TSUNDOKU = TachiyomiApkEcosystemSpec(
        extensionType = ExternalExtensionType.TSUNDOKU,
        ecosystemDir = "tsundoku",
        sourcePrefix = "TSUNDOKU_",
        requiredFeature = "tachiyomi.novelextension",
        sourceMetadataKey = "tachiyomi.novelextension.class",
        factoryMetadataKey = "tachiyomi.novelextension.factory",
        acceptedLibVersions = setOf("1.4", "1.6"),
        strictIdentification = true,
    )

    val all: List<TachiyomiApkEcosystemSpec> = listOf(MIHON, TSUNDOKU)

    fun forType(type: ExternalExtensionType): TachiyomiApkEcosystemSpec? = all.firstOrNull {
        it.extensionType == type
    }

    private fun formatLibVersion(version: Double): String = version.toString()
}
