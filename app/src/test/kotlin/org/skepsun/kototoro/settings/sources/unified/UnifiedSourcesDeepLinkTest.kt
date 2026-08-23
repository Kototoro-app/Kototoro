package org.skepsun.kototoro.settings.sources.unified

import android.net.Uri
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure parsing tests for [UnifiedSourcesDeepLinkParser] (Tsundoku Phase 5 / T5.2).
 *
 * Runs on the JVM. `android.net.Uri` and `android.os.Bundle` are mocked (established repo
 * pattern for framework classes — their real implementations are not functional in plain
 * JVM unit tests). The parser itself only touches these two types plus plain Kotlin.
 */
class UnifiedSourcesDeepLinkTest {

    private fun uriWithParameters(vararg params: Pair<String, String?>): Uri {
        return mockk<Uri> {
            every { getQueryParameter("tab") } returns params.firstOrNull { it.first == "tab" }?.second
            every { getQueryParameter("package") } returns params.firstOrNull { it.first == "package" }?.second
            every { getQueryParameter("source") } returns params.firstOrNull { it.first == "source" }?.second
        }
    }

    private fun bundleWith(vararg params: Pair<String, String?>): Bundle {
        return mockk<Bundle> {
            every { isEmpty } returns false
            every { getString(UnifiedSourcesDeepLinkParser.EXTRA_INITIAL_TAB) } returns
                params.firstOrNull { it.first == UnifiedSourcesDeepLinkParser.EXTRA_INITIAL_TAB }?.second
            every { getString(UnifiedSourcesDeepLinkParser.EXTRA_PACKAGE_FILTER) } returns
                params.firstOrNull { it.first == UnifiedSourcesDeepLinkParser.EXTRA_PACKAGE_FILTER }?.second
            every { getString(UnifiedSourcesDeepLinkParser.EXTRA_SOURCE_KEY) } returns
                params.firstOrNull { it.first == UnifiedSourcesDeepLinkParser.EXTRA_SOURCE_KEY }?.second
        }
    }

    @Test
    fun `all uri parameters parse`() {
        val link = UnifiedSourcesDeepLinkParser.fromUri(
            uriWithParameters(
                "tab" to "Recovery",
                "package" to "com.example.ext",
                "source" to "TSUNDOKU_9001",
            ),
        )
        assertEquals(UnifiedSourcesDeepLinkParser.TAB_RECOVERY, link.initialTab)
        assertEquals("com.example.ext", link.packageFilter)
        assertEquals("TSUNDOKU_9001", link.sourceKey)
    }

    @Test
    fun `partial uri parameters parse and missing ones stay null`() {
        val link = UnifiedSourcesDeepLinkParser.fromUri(
            uriWithParameters("package" to "com.example.ext"),
        )
        assertNull(link.initialTab)
        assertEquals("com.example.ext", link.packageFilter)
        assertNull(link.sourceKey)
    }

    @Test
    fun `unknown tab resolves to null`() {
        val link = UnifiedSourcesDeepLinkParser.fromUri(
            uriWithParameters("tab" to "favorites", "package" to "com.example.ext"),
        )
        assertNull(link.initialTab)
        // Non-tab parameters are still parsed.
        assertEquals("com.example.ext", link.packageFilter)
    }

    @Test
    fun `tab matching is case insensitive`() {
        assertEquals(
            UnifiedSourcesDeepLinkParser.TAB_SOURCES,
            UnifiedSourcesDeepLinkParser.fromUri(uriWithParameters("tab" to "SOURCES")).initialTab,
        )
        assertEquals(
            UnifiedSourcesDeepLinkParser.TAB_REPOS,
            UnifiedSourcesDeepLinkParser.fromUri(uriWithParameters("tab" to "Repositories")).initialTab,
        )
        assertEquals(
            UnifiedSourcesDeepLinkParser.TAB_INSTALLED,
            UnifiedSourcesDeepLinkParser.fromUri(uriWithParameters("tab" to "PACKAGES")).initialTab,
        )
        assertEquals(
            UnifiedSourcesDeepLinkParser.TAB_RECOVERY,
            UnifiedSourcesDeepLinkParser.fromUri(uriWithParameters("tab" to "recovery")).initialTab,
        )
    }

    @Test
    fun `values are trimmed and blank values become null`() {
        val link = UnifiedSourcesDeepLinkParser.fromUri(
            uriWithParameters(
                "tab" to "   ",
                "package" to "  com.example.ext  ",
                "source" to "",
            ),
        )
        assertNull(link.initialTab)
        assertEquals("com.example.ext", link.packageFilter)
        assertNull(link.sourceKey)
    }

    @Test
    fun `uri without any known parameters yields an empty link`() {
        assertEquals(
            UnifiedSourcesDeepLink(),
            UnifiedSourcesDeepLinkParser.fromUri(uriWithParameters("other" to "value")),
        )
        // Every parameter present but blank.
        assertEquals(
            UnifiedSourcesDeepLink(),
            UnifiedSourcesDeepLinkParser.fromUri(
                uriWithParameters("tab" to "  ", "package" to "  ", "source" to "  "),
            ),
        )
    }

    @Test
    fun `extras parse and missing keys stay null`() {
        val extras = bundleWith(
            UnifiedSourcesDeepLinkParser.EXTRA_INITIAL_TAB to "installed",
            UnifiedSourcesDeepLinkParser.EXTRA_SOURCE_KEY to "TSUNDOKU_9001",
        )
        val link = UnifiedSourcesDeepLinkParser.fromExtras(extras)
        assertEquals(UnifiedSourcesDeepLinkParser.TAB_INSTALLED, link.initialTab)
        assertNull(link.packageFilter)
        assertEquals("TSUNDOKU_9001", link.sourceKey)
    }

    @Test
    fun `null and empty extras yield an empty link`() {
        assertEquals(UnifiedSourcesDeepLink(), UnifiedSourcesDeepLinkParser.fromExtras(null))
        assertEquals(UnifiedSourcesDeepLink(), UnifiedSourcesDeepLinkParser.fromExtras(Bundle()))
    }

    @Test
    fun `uri takes precedence over extras on every field`() {
        val uri = UnifiedSourcesDeepLink(
            initialTab = UnifiedSourcesDeepLinkParser.TAB_RECOVERY,
            packageFilter = "uri-pkg",
            sourceKey = null,
        )
        val extras = UnifiedSourcesDeepLink(
            initialTab = UnifiedSourcesDeepLinkParser.TAB_SOURCES,
            packageFilter = "extras-pkg",
            sourceKey = "TSUNDOKU_9001",
        )
        val merged = UnifiedSourcesDeepLinkParser.merge(uri, extras)
        assertEquals(UnifiedSourcesDeepLinkParser.TAB_RECOVERY, merged.initialTab)
        assertEquals("uri-pkg", merged.packageFilter)
        // Uri left this field null, so the extras fill the gap.
        assertEquals("TSUNDOKU_9001", merged.sourceKey)
    }

    @Test
    fun `extras unknown tab resolves to null but keep other params`() {
        val extras = bundleWith(
            UnifiedSourcesDeepLinkParser.EXTRA_INITIAL_TAB to "weird",
            UnifiedSourcesDeepLinkParser.EXTRA_PACKAGE_FILTER to "com.example",
        )
        val link = UnifiedSourcesDeepLinkParser.fromExtras(extras)
        assertNull(link.initialTab)
        assertEquals("com.example", link.packageFilter)
        assertNull(link.sourceKey)
    }
}
