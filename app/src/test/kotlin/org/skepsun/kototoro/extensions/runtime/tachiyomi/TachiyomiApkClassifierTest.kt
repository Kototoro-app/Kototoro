package org.skepsun.kototoro.extensions.runtime.tachiyomi

import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TachiyomiApkClassifierTest {

    /**
     * PackageInfo/ApplicationInfo expose public fields (not properties), so the repo pattern
     * is real instances; only the manifest Bundle is a strict MockK mock (see
     * MihonExtensionLoaderContractTest).
     */
    private fun pkgInfo(
        packageName: String,
        features: List<String> = emptyList(),
        sourceClassDeclared: Boolean = false,
    ): PackageInfo {
        val metaData = mockk<Bundle>()
        every { metaData.containsKey("tachiyomi.extension.class") } returns sourceClassDeclared
        every { metaData.containsKey("tachiyomi.extension.factory") } returns false
        val appInfo = ApplicationInfo().apply {
            this.metaData = metaData
        }
        return PackageInfo().apply {
            this.packageName = packageName
            reqFeatures = features.map { name ->
                FeatureInfo().apply { this.name = name }
            }.toTypedArray()
            applicationInfo = appInfo
        }
    }

    // ---- Mihon loose mode (reproduces the pinned loader behavior exactly) ----

    @Test
    fun `mihon loose mode accepts a package declaring the tachiyomi extension feature`() {
        val pkg = pkgInfo("com.example.extension", features = listOf("tachiyomi.extension"))
        assertEquals(
            TachiyomiApkClassification.Extension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.MIHON),
        )
    }

    @Test
    fun `mihon loose mode accepts a tachiyomi-looking package with source metadata but no feature`() {
        val pkg = pkgInfo("eu.kanade.tachiyomi.extension.en.example", sourceClassDeclared = true)
        assertEquals(
            TachiyomiApkClassification.Extension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.MIHON),
        )
    }

    @Test
    fun `mihon loose mode rejects non-tachiyomi packages without feature`() {
        val pkg = pkgInfo("com.some.app")
        assertEquals(
            TachiyomiApkClassification.NotAnExtension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.MIHON),
        )
    }

    @Test
    fun `mihon loose mode never reports ambiguous even with a novel feature present`() {
        val pkg = pkgInfo(
            "com.example.extension",
            features = listOf("tachiyomi.extension", "tachiyomi.novelextension"),
        )
        assertEquals(
            TachiyomiApkClassification.Extension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.MIHON),
        )
    }

    // ---- Tsundoku strict mode (plan §7.2) ----

    @Test
    fun `tsundoku strict mode accepts a novel feature package`() {
        val pkg = pkgInfo("eu.kanade.tachiyomi.extension.en.novel-example", features = listOf("tachiyomi.novelextension"))
        assertEquals(
            TachiyomiApkClassification.Extension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `tsundoku strict mode rejects a mihon-looking package with metadata but no novel feature`() {
        val pkg = pkgInfo("eu.kanade.tachiyomi.extension.en.example", sourceClassDeclared = true)
        assertEquals(
            TachiyomiApkClassification.NotAnExtension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `tsundoku strict mode rejects a plain manga package`() {
        val pkg = pkgInfo("com.example.extension", features = listOf("tachiyomi.extension"))
        assertEquals(
            TachiyomiApkClassification.NotAnExtension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `tsundoku strict mode reports ambiguous when both novel and manga features are declared`() {
        val pkg = pkgInfo(
            "com.example.extension",
            features = listOf("tachiyomi.extension", "tachiyomi.novelextension"),
        )
        assertEquals(
            TachiyomiApkClassification.Ambiguous,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `tsundoku strict mode never classifies by package name alone`() {
        val pkg = pkgInfo("eu.kanade.tachiyomi.novelextension.en.example")
        assertEquals(
            TachiyomiApkClassification.NotAnExtension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `tsundoku strict mode with no novel feature but source metadata is rejected`() {
        val pkg = pkgInfo("com.example.extension", sourceClassDeclared = true)
        assertEquals(
            TachiyomiApkClassification.NotAnExtension,
            TachiyomiApkClassifier.classify(pkg, TachiyomiApkEcosystemSpecs.TSUNDOKU),
        )
    }

    @Test
    fun `mihon and tsundoku features are distinct`() {
        assertTrue(TachiyomiApkEcosystemSpecs.MIHON.requiredFeature != TachiyomiApkEcosystemSpecs.TSUNDOKU.requiredFeature)
    }
}
