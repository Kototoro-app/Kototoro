package org.skepsun.kototoro.core.exceptions.resolve

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.TestContentSource

class ExceptionResolverTest {

    @Test
    fun `wrapped Cloudflare challenge remains resolvable`() {
        val challenge = CloudFlareProtectedException(
            url = "https://example.org/",
            source = TestContentSource,
            headers = Headers.headersOf("User-Agent", "test"),
        )

        assertEquals(
            R.string.captcha_solve,
            ExceptionResolver.getResolveStringId(java.io.IOException("wrapped", challenge)),
        )
    }
}
