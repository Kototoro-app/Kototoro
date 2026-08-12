package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserOriginPolicyTest {
    @Test
    fun `request origin is allowed by default`() {
        val policy = BrowserOriginPolicy.create("https://example.com/api/items?id=1")!!
        assertNotNull(policy)

        assertEquals("https://example.com", policy.primaryOrigin)
		assertTrue(policy.allowsDocument("https://example.com/other"))
		assertTrue(policy.allowsFetch("https://example.com/other"))
		assertTrue(policy.allowsRedirect("https://example.com/other"))
		assertFalse(policy.allowsFetch("https://api.example.com/items"))
    }

    @Test
    fun `explicit api origin is allowed`() {
        val policy = BrowserOriginPolicy.create(

                requestUrl = "https://www.example.com/manga",
                additionalOrigins = setOf("https://api.example.com"),
            )!!
        assertNotNull(policy)

		assertTrue(policy.allowsFetch("https://api.example.com/v1/manga"))
		assertFalse(policy.allowsDocument("https://api.example.com/v1/manga"))
		assertFalse(policy.allowsRedirect("https://api.example.com/v1/manga"))
		assertFalse(policy.allowsFetch("https://cdn.example.com/image.jpg"))
    }

    @Test
    fun `scheme and non-default port are part of origin`() {
        val policy = BrowserOriginPolicy.create("https://example.com:8443/api")!!
        assertNotNull(policy)

        assertEquals("https://example.com:8443", policy.primaryOrigin)
		assertTrue(policy.allowsFetch("https://example.com:8443/other"))
		assertFalse(policy.allowsFetch("https://example.com/other"))
		assertFalse(policy.allowsFetch("http://example.com:8443/other"))
    }

    @Test
    fun `unsafe or malformed policy is rejected`() {
        assertNull(BrowserOriginPolicy.create("http://example.com/api"))
        assertNull(
            BrowserOriginPolicy.create(
                requestUrl = "https://example.com/api",
                additionalOrigins = setOf("https://api.example.com/path"),
            ),
        )
        assertNull(
            BrowserOriginPolicy.create(
                requestUrl = "https://example.com/api",
                additionalOrigins = setOf("file:///tmp/data"),
            ),
        )
		assertNull(BrowserOriginPolicy.create("https://localhost/api"))
		assertNull(BrowserOriginPolicy.create("https://127.0.0.1/api"))
		assertNull(BrowserOriginPolicy.create("https://192.168.1.20/api"))
		assertNull(BrowserOriginPolicy.create("https://[::1]/api"))
		assertNull(
			BrowserOriginPolicy.create(
				"https://example.com/api",
				additionalOrigins = setOf("https://10.0.0.4"),
			),
		)
    }
}
