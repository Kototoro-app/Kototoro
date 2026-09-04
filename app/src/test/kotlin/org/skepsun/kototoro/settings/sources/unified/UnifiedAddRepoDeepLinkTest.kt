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
    fun `kind and url parse from uri`() {
        val link = UnifiedAddRepoDeepLinkParser.fromUri(
            uriWithParameters(
                UnifiedAddRepoDeepLinkParser.PARAM_KIND to "TVBOX",
                UnifiedAddRepoDeepLinkParser.PARAM_URL to "http://z.qiqiv.cn/123.txt",
            ),
        )
        assertEquals(UnifiedSourceKind.TVBOX, link.kind)
        assertEquals("http://z.qiqiv.cn/123.txt", link.url)
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
                UnifiedAddRepoDeepLinkParser.PARAM_KIND to "cloudstream",
                UnifiedAddRepoDeepLinkParser.PARAM_URL to "https://example.com/repo.json",
            ),
        )
        assertEquals(UnifiedSourceKind.CLOUDSTREAM, link.kind)
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
        assertEquals(UnifiedSourceKind.ANIYOMI, UnifiedAddRepoDeepLinkParser.kindFromScheme("aniyomi"))
        assertEquals(UnifiedSourceKind.ANIYOMI, UnifiedAddRepoDeepLinkParser.kindFromScheme("anikku"))
        assertEquals(UnifiedSourceKind.MIHON, UnifiedAddRepoDeepLinkParser.kindFromScheme("tachiyomi"))
        assertEquals(UnifiedSourceKind.MIHON, UnifiedAddRepoDeepLinkParser.kindFromScheme("kototoro"))
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme("tvbox"))
        assertNull(UnifiedAddRepoDeepLinkParser.kindFromScheme(null))
    }
}
