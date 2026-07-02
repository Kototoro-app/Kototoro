package org.skepsun.kototoro.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

class CloudFlareHelperTest {

    @Test
    fun `normal Cloudflare served page with turnstile script is not treated as captcha`() {
        val response = response(
            code = 200,
            body = """
                <!doctype html>
                <html>
                    <head>
                        <title>Example</title>
                        <script src="https://challenges.cloudflare.com/turnstile/v0/api.js"></script>
                    </head>
                    <body>
                        <main>Normal site content</main>
                        <div class="cf-turnstile"></div>
                    </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(
            CloudFlareHelper.PROTECTION_NOT_DETECTED,
            CloudFlareHelper.checkResponseForProtection(response),
        )
    }

    @Test
    fun `cloudflare challenge response is treated as captcha`() {
        val response = response(
            code = 403,
            body = """
                <!doctype html>
                <html>
                    <head><title>Just a moment...</title></head>
                    <body>
                        <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
                    </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(
            CloudFlareHelper.PROTECTION_CAPTCHA,
            CloudFlareHelper.checkResponseForProtection(response),
        )
    }

    private fun response(code: Int, body: String): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.org/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .header("server", "cloudflare")
            .header("content-type", "text/html; charset=utf-8")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }
}
