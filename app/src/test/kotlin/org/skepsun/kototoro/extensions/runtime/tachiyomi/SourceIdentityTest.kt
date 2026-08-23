package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

class SourceIdentityTest {

    @Test
    fun `tsundoku source key uses TSUNDOKU prefix and numeric id`() {
        val identity = SourceIdentity(ExternalExtensionType.TSUNDOKU, 9001)
        assertEquals("TSUNDOKU_9001", identity.sourceKey)
    }

    @Test
    fun `mihon source key keeps the existing MIHON convention`() {
        assertEquals("MIHON_123", SourceIdentity(ExternalExtensionType.MIHON, 123).sourceKey)
    }

    @Test
    fun `fromSourceKey round trips both ecosystems`() {
        assertEquals(
            SourceIdentity(ExternalExtensionType.TSUNDOKU, 9001),
            SourceIdentity.fromSourceKey("TSUNDOKU_9001"),
        )
        assertEquals(
            SourceIdentity(ExternalExtensionType.MIHON, 42),
            SourceIdentity.fromSourceKey("MIHON_42"),
        )
    }

    @Test
    fun `fromSourceKey rejects non numeric and unknown prefixes`() {
        assertNull(SourceIdentity.fromSourceKey("TSUNDOKU_abc"))
        assertNull(SourceIdentity.fromSourceKey("TSUNDOKU_"))
        assertNull(SourceIdentity.fromSourceKey("UNKNOWN_12"))
    }

    @Test
    fun `tsundoku and mihon package keys stay distinct for the same package name`() {
        val mihon = ExternalApkPackageKey(ExternalExtensionType.MIHON, "com.example.extension")
        val tsundoku = ExternalApkPackageKey(ExternalExtensionType.TSUNDOKU, "com.example.extension")
        assertTrue(mihon != tsundoku, "same packageName across ecosystems must not collide")
        assertEquals(mihon.copy(), mihon)
    }

    @Test
    fun `tsundoku ecosystem spec accepts only lib versions 1_4 and 1_6`() {
        val spec = TachiyomiApkEcosystemSpecs.TSUNDOKU
        assertEquals(ExternalExtensionType.TSUNDOKU, spec.extensionType)
        assertEquals("tsundoku", spec.ecosystemDir)
        assertEquals("tachiyomi.novelextension", spec.requiredFeature)
        assertEquals(setOf("1.4", "1.6"), spec.acceptedLibVersions)
        assertTrue(spec.strictIdentification)
    }

    @Test
    fun `mihon ecosystem spec preserves the historical loose identification`() {
        val spec = TachiyomiApkEcosystemSpecs.MIHON
        assertEquals("mihon", spec.ecosystemDir)
        assertEquals("tachiyomi.extension", spec.requiredFeature)
        assertTrue(!spec.strictIdentification)
        assertTrue(spec.acceptedLibVersions.contains("1.2"))
        assertTrue(spec.acceptedLibVersions.contains("1.9"))
    }

    @Test
    fun `tachiyomiXSourceAdapterData derives key and preference namespace`() {
        val adapter = TachiyomiXSourceAdapterData(
            ecosystem = ExternalExtensionType.TSUNDOKU,
            packageName = "eu.kanade.tachiyomi.extension.en.novel-example",
            sourceId = 9001,
            upstreamSource = io.mockk.mockk<eu.kanade.tachiyomi.source.Source>(),
            contentType = org.skepsun.kototoro.parsers.model.ContentType.NOVEL,
            baseUrlOrNull = "https://example.org",
        )
        assertEquals("TSUNDOKU_9001", adapter.sourceKey)
        assertEquals(
            "tsundoku:eu.kanade.tachiyomi.extension.en.novel-example:9001",
            adapter.preferenceNamespace,
        )
        assertEquals(SourceIdentity(ExternalExtensionType.TSUNDOKU, 9001), adapter.identity)
    }
}
