package org.skepsun.kototoro.desktop.compat

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.cloudflare.ClearanceSolver
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DesktopChallengeInterceptorTest {
    private fun challenges() = DesktopBrowserChallenges(File("unused-fixture.exe"), Path.of("unused-profile"),
        { error("Ordinary HTTP errors must not start a browser") })
    private val unsolvable = ClearanceSolver { false }

    @Test
    fun `preserved SDK interceptor never receives ordinary Cloudflare errors for its server-only resolver`() {
        for (status in listOf(403, 503)) challenges().use { owner ->
            val sdk = eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor { }
            val client = OkHttpClient.Builder().addInterceptor(sdk)
                .addInterceptor(DesktopChallengeInterceptor(owner, guardSdkHandler = true, solver = unsolvable))
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
            val solves = AtomicInteger()
            val client = OkHttpClient.Builder()
                .addInterceptor(DesktopChallengeInterceptor(owner, solver = ClearanceSolver { solves.incrementAndGet(); false }))
                .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("ordinary forbidden").header("Server", "cloudflare")
                    .body("fixture".toResponseBody()).build() }.build()
            client.newCall(Request.Builder().url("https://fixture.invalid/").build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("fixture", it.body.string())
            }
            // Mihon's rule still tries the hidden solver once; the unsolved error page comes back unchanged.
            assertEquals(1, solves.get())
            assertNull(owner.pending.value)
        }
    }

    @Test
    fun `explicit challenge is solved off screen and retried without asking the user`() {
        challenges().use { owner ->
            var requests = 0
            val solved = AtomicInteger()
            val client = OkHttpClient.Builder()
                .addInterceptor(DesktopChallengeInterceptor(owner, solver = ClearanceSolver { solved.incrementAndGet(); true }))
                .addInterceptor { chain ->
                    val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    if (requests++ == 0) {
                        builder.code(403).message("challenge").header("cf-mitigated", "challenge")
                            .body("challenge".toResponseBody()).build()
                    } else {
                        builder.code(200).message("OK").body("content".toResponseBody()).build()
                    }
                }.build()
            client.newCall(Request.Builder().url("https://fixture.invalid/").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("content", it.body.string())
            }
            assertEquals(1, solved.get())
            assertEquals(2, requests)
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
