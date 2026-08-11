package org.skepsun.kototoro.core.network.webview

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.CommonHeaders

class CloudFlareWebViewHeadersTest {

    @Test
    fun `keeps request context required by protected page`() {
        val headers = Headers.Builder()
            .add(CommonHeaders.REFERER, "https://example.org/details")
            .add("Origin", "https://example.org")
            .add(CommonHeaders.ACCEPT, "text/html")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        assertEquals(
            mapOf(
                CommonHeaders.ACCEPT to "text/html",
                "Origin" to "https://example.org",
                CommonHeaders.REFERER to "https://example.org/details",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            headers.toCloudFlareWebViewHeaders(),
        )
    }

    @Test
    fun `drops headers managed by WebView or internal to host`() {
        val headers = Headers.Builder()
            .add(CommonHeaders.USER_AGENT, "test-agent")
            .add(CommonHeaders.COOKIE, "cf_clearance=secret")
            .add(CommonHeaders.AUTHORIZATION, "Bearer secret")
            .add(CommonHeaders.PROXY_AUTHORIZATION, "Basic secret")
            .add(CommonHeaders.ACCEPT_ENCODING, "gzip")
            .add(CommonHeaders.MANGA_SOURCE, "MIHON_1")
            .add("Host", "example.org")
            .add("Connection", "keep-alive")
            .build()

        val result = headers.toCloudFlareWebViewHeaders()

        assertFalse(result.keys.any { it.equals(CommonHeaders.USER_AGENT, ignoreCase = true) })
        assertFalse(result.keys.any { it.equals(CommonHeaders.COOKIE, ignoreCase = true) })
        assertFalse(result.keys.any { it.equals(CommonHeaders.AUTHORIZATION, ignoreCase = true) })
        assertFalse(result.keys.any { it.equals(CommonHeaders.PROXY_AUTHORIZATION, ignoreCase = true) })
        assertFalse(result.keys.any { it.equals(CommonHeaders.MANGA_SOURCE, ignoreCase = true) })
        assertFalse(result.keys.any { it.equals("Host", ignoreCase = true) })
        assertFalse(result.keys.any { it.equals("Connection", ignoreCase = true) })
    }
}
