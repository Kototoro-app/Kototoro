package org.skepsun.kototoro.settings.sources.unified

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure JVM parsing tests for [UnifiedAddRepoDeepLinkParser].
 *
 * `android.net.Uri` is mocked, mirroring the established repo pattern used by
 * [UnifiedSourcesDeepLinkTest]: the parser only touches `Uri.getQueryParameter`.
 */
class UnifiedAddRepoDeepLinkTest {

    private fun uriWithParameters(vararg params: Pair<String, String?>): Uri {
        return mockk<Uri> {
            every { getQueryParameter(UnifiedAddRepoDeepLinkParser.PARAM_KIND) } returns
                params.firstOrNull { it.first == UnifiedAddRepoDeepLinkParser.PARAM_KIND }?.second
            every { getQueryParameter(UnifiedAddRepoDeepLinkParser.PARAM_URL) } returns
                params.firstOrNull { it.first == UnifiedAddRepoDeepLinkParser.PARAM_URL }?.second
        }
    }

    @Test
    fun `retired audiovisual repository links are rejected`() {
        for (kind in listOf("TVBOX", "ANIYOMI", "CLOUDSTREAM")) {
            val link = UnifiedAddRepoDeepLinkParser.fromUri(
                uriWithParameters(
                    UnifiedAddRepoDeepLinkParser.PARAM_KIND to kind,
                    UnifiedAddRepoDeepLinkParser.PARAM_URL to "https://example.com/repo.json",
                ),
            )
            assertEquals(UnifiedAddRepoDeepLink.EMPTY, link)
        }
    }

    @Test
    fun `missing parameters resolve to null`() {
        val link = UnifiedAddRepoDeepLinkParser.fromUri(
            uriWithParameters(UnifiedAddRepoDeepLinkParser.PARAM_URL to "https://example.com/index.json"),
        )
        assertNull(link.kind)
        assertEquals("https://example.com/index.json", link.url)
    }

    @Test
    fun `blank kind and url resolve to null`() {
        val link = UnifiedAddRepoDeepLinkParser.fromUri(
            uriWithParameters(
                UnifiedAddRepoDeepLinkParser.PARAM_KIND to " ",
                UnifiedAddRepoDeepLinkParser.PARAM_URL to "",
            ),
        )
        assertNull(link.kind)
        assertNull(link.url)
    }

    @Test
    fun `kind matching is case insensitive`() {
        val link = UnifiedAddRepoDeepLinkParser.fromUri(
            uriWithParameters(
                UnifiedAddRepoDeepLinkParser.PARAM_KIND to "mIhOn",
                UnifiedAddRepoDeepLinkParser.PARAM_URL to "https://example.com/repo.json",
            ),
        )
        assertEquals(UnifiedSourceKind.MIHON, link.kind)
    }

    @Test
    fun `unknown kind resolves to null`() {
        val link = UnifiedAddRepoDeepLinkParser.fromUri(
            uriWithParameters(UnifiedAddRepoDeepLinkParser.PARAM_KIND to "NOPE"),
        )
        assertNull(link.kind)
    }

    @Test
    fun `null uri resolves to empty link`() {
        assertEquals(UnifiedAddRepoDeepLink.EMPTY, UnifiedAddRepoDeepLinkParser.fromUri(null))
    }

    @Test
    fun `scheme fallback keeps legacy mappings`() {
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme("aniyomi"))
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme("anikku"))
        assertEquals(UnifiedSourceKind.MIHON, UnifiedAddRepoDeepLinkParser.kindFromScheme("tachiyomi"))
        assertEquals(UnifiedSourceKind.MIHON, UnifiedAddRepoDeepLinkParser.kindFromScheme("kototoro"))
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme("tvbox"))
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme(null))
    }
}
