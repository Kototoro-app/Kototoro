package org.skepsun.kototoro.desktop.compat

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Path

class DesktopChallengeInterceptorTest {
    private fun challenges() = DesktopBrowserChallenges(File("unused-fixture.exe"), Path.of("unused-profile"),
        { error("Ordinary HTTP errors must not start a browser") })

    @Test
    fun `preserved SDK interceptor never receives ordinary Cloudflare errors for its server-only resolver`() {
        for (status in listOf(403, 503)) challenges().use { owner ->
            val sdk = eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor { }
            val client = OkHttpClient.Builder().addInterceptor(sdk)
                .addInterceptor(DesktopChallengeInterceptor(owner, guardSdkHandler = true))
                .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("ordinary forbidden").header("Server", "cloudflare")
                    .body("fixture".toResponseBody()).build() }.build()
            assertSame(sdk, client.interceptors.first())
            val failure = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("https://fixture.invalid/").build()).execute().close()
            }
            assertEquals("来源访问被拒绝：HTTP $status", failure.message)
            assertNull(owner.pending.value)
        }
    }

    @Test
    fun `ordinary Cloudflare server error without a challenge marker does not open a browser`() {
        challenges().use { owner ->
            val client = OkHttpClient.Builder().addInterceptor(DesktopChallengeInterceptor(owner))
                .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("ordinary forbidden").header("Server", "cloudflare")
                    .body("fixture".toResponseBody()).build() }.build()
            client.newCall(Request.Builder().url("https://fixture.invalid/").build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("fixture", it.body.string())
            }
            assertNull(owner.pending.value)
        }
    }

    @Test
    fun `a challenged POST is not replayed or sent to a browser`() {
        challenges().use { owner ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor(DesktopChallengeInterceptor(owner))
                .addInterceptor { chain ->
                    requests++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(403).message("challenge").header("cf-mitigated", "challenge")
                        .body("fixture".toResponseBody()).build()
                }.build()
            val failure = runCatching { client.newCall(Request.Builder().url("https://fixture.invalid/")
                .post("fixture".toRequestBody()).build()).execute() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message?.contains("POST") == true)
            assertEquals(1, requests)
            assertNull(owner.pending.value)
        }
    }
}
