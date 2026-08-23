package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.PackageInfo
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionMetadataSupport

/**
 * Outcome of classifying one APK against one [TachiyomiApkEcosystemSpec].
 */
sealed interface TachiyomiApkClassification {
    /** The APK belongs to this ecosystem and may be scanned/loaded. */
    data object Extension : TachiyomiApkClassification

    /** The APK does not belong to this ecosystem. */
    data object NotAnExtension : TachiyomiApkClassification

    /**
     * Strict ecosystems: the APK declares both this ecosystem's feature and the manga feature
     * (e.g. `tachiyomi.novelextension` + `tachiyomi.extension`). Per plan §7.2 it must be
     * rejected rather than guessed. Only produced in strict mode.
     */
    data object Ambiguous : TachiyomiApkClassification
}

/**
 * Ecosystem-neutral APK classifier (plan §7.2, T1.2).
 *
 * Mihon keeps its historical loose identification: feature declaration OR
 * (Tachiyomi-looking package name AND declared source/factory metadata). Tsundoku uses strict
 * identification: only the `tachiyomi.novelextension` feature classifies an APK; package name,
 * class name or plain Mihon metadata never imply Tsundoku; declaring both novel and manga
 * features yields [TachiyomiApkClassification.Ambiguous].
 *
 * This is the single classification seam both loaders go through, so a Mihon APK is never
 * misread as a Tsundoku extension and vice versa.
 */
object TachiyomiApkClassifier {

    /** Mirrors the legacy Mihon package-name heuristic (`ExternalExtensionLoaderSupport`). */
    fun looksLikeTachiyomiPackage(packageName: String): Boolean {
        return ExternalExtensionLoaderSupport.looksLikeMihonPackage(packageName)
    }

    fun classify(
        pkgInfo: PackageInfo,
        spec: TachiyomiApkEcosystemSpec,
    ): TachiyomiApkClassification {
        val declaresOwnFeature = pkgInfo.reqFeatures?.any { it.name == spec.requiredFeature } == true

        if (spec.strictIdentification) {
            // Tsundoku strict rules (plan §7.2): feature only; double-feature is ambiguous.
            if (declaresOwnFeature) {
                val declaresMangaFeature = pkgInfo.reqFeatures?.any {
                    it.name == TachiyomiApkEcosystemSpecs.MIHON.requiredFeature
                } == true
                return if (declaresMangaFeature) {
                    TachiyomiApkClassification.Ambiguous
                } else {
                    TachiyomiApkClassification.Extension
                }
            }
            return TachiyomiApkClassification.NotAnExtension
        }

        // Mihon loose rules, reproduced exactly: feature OR (name AND metadata).
        val hasPackageName = looksLikeTachiyomiPackage(pkgInfo.packageName)
        val hasMetaData = ExternalExtensionMetadataSupport.hasDeclaredSource(
            metaData = pkgInfo.applicationInfo?.metaData,
            sourceClassKey = spec.sourceMetadataKey,
            sourceFactoryKey = spec.factoryMetadataKey.orEmpty(),
        )
        return if (declaresOwnFeature || (hasPackageName && hasMetaData)) {
            TachiyomiApkClassification.Extension
        } else {
            TachiyomiApkClassification.NotAnExtension
        }
    }
}
