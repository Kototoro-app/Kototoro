package org.skepsun.kototoro.core.network.cloudflare

import kotlinx.coroutines.awaitCancellation
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.skepsun.kototoro.core.network.CloudflareHostCooldown
import org.skepsun.kototoro.core.network.webview.CloudFlarePageState
import org.skepsun.kototoro.core.network.webview.CloudflareSolveCoordinator
import java.util.concurrent.atomic.AtomicInteger

class ClearanceSolvingTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `Mihon trigger is a Cloudflare 403 or 503, or the explicit challenge marker`() {
        assertTrue(isMihonCloudflareChallenge(403, "cloudflare", null))
        assertTrue(isMihonCloudflareChallenge(503, "Cloudflare-Nginx", null))
        assertTrue(isMihonCloudflareChallenge(200, null, "Challenge"))
        assertFalse(isMihonCloudflareChallenge(403, "nginx", null))
        assertFalse(isMihonCloudflareChallenge(404, "cloudflare", null))
        assertFalse(isMihonCloudflareChallenge(200, "cloudflare", null))
    }

    @Test
    fun `browser headers drop unsafe names, cookies and duplicate values`() {
        val headers = headersOf(
            "User-Agent", "UA", "Referer", "https://a/", "Host", "a", "Content-Length", "3",
            "Proxy-Authorization", "x", "Connection", "Upgrade", "Cookie", "a=b", "Accept", "1", "Accept", "2",
        ).safeForBrowser()
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://a/", "Accept" to "1"), headers)
        assertTrue(isBrowserRequestHeaderSafe("Connection", "keep-alive"))
    }

    @Test
    fun `event rules match Mihon's WebView solver`() {
        val url = "https://site.test/page"
        ClearanceSolveTracker(url).run {
            assertEquals(ClearanceSolveDecision.WAIT, onMainFrameHttpError(503))
            assertTrue(challengeFound)
            // The challenge page finishing is not a failure; the JS keeps running.
            assertEquals(ClearanceSolveDecision.WAIT, onPageFinished(url, hasNewClearance = false))
            assertEquals(ClearanceSolveDecision.SOLVED, onPageFinished(url, hasNewClearance = true))
        }
        ClearanceSolveTracker(url).run {
            assertEquals(ClearanceSolveDecision.FAILED, onPageFinished(url, hasNewClearance = false))
        }
        ClearanceSolveTracker(url).run {
            assertEquals(ClearanceSolveDecision.WAIT, onPageFinished("https://site.test/redirect", false))
            assertEquals(ClearanceSolveDecision.FAILED, onMainFrameHttpError(500))
        }
    }

    @Test
    fun `polled page states fail fast where a hidden browser cannot pass`() {
        ClearanceSolveTracker("u").run {
            assertEquals(ClearanceSolveDecision.WAIT, onPageState(CloudFlarePageState.LOADING, false, 0))
            assertEquals(ClearanceSolveDecision.WAIT, onPageState(CloudFlarePageState.MANAGED_CHALLENGE, false, 0))
            // After a managed challenge the page reloads; it counts once the new clearance appears.
            assertEquals(ClearanceSolveDecision.WAIT, onPageState(CloudFlarePageState.NORMAL, false, 0))
            assertEquals(ClearanceSolveDecision.SOLVED, onPageState(CloudFlarePageState.NORMAL, true, 0))
        }
        assertEquals(ClearanceSolveDecision.FAILED,
            ClearanceSolveTracker("u").onPageState(CloudFlarePageState.NORMAL, false, 0))
        assertEquals(ClearanceSolveDecision.FAILED,
            ClearanceSolveTracker("u").onPageState(CloudFlarePageState.HARD_BLOCK, false, 0))
        ClearanceSolveTracker("u").run {
            val grace = ClearanceSolveTracker.INTERACTIVE_GRACE_MS
            // Turnstile may show its widget briefly and pass by itself; only a lasting checkbox needs a human.
            assertEquals(ClearanceSolveDecision.WAIT, onPageState(CloudFlarePageState.INTERACTIVE_CHALLENGE, false, 1000))
            assertEquals(ClearanceSolveDecision.WAIT, onPageState(CloudFlarePageState.MANAGED_CHALLENGE, false, 2000))
            assertEquals(ClearanceSolveDecision.WAIT,
                onPageState(CloudFlarePageState.INTERACTIVE_CHALLENGE, false, 3000))
            assertEquals(ClearanceSolveDecision.WAIT,
                onPageState(CloudFlarePageState.INTERACTIVE_CHALLENGE, false, 3000 + grace - 1))
            assertEquals(ClearanceSolveDecision.FAILED,
                onPageState(CloudFlarePageState.INTERACTIVE_CHALLENGE, false, 3000 + grace))
        }
    }

    @Test
    fun `solved challenge retries the request once and concurrent hosts share one solve`() {
        server.enqueue(challenge())
        server.enqueue(MockResponse().setBody("ok"))
        val solves = AtomicInteger()
        val client = clientWith(ClearanceSolver { solves.incrementAndGet(); true })
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("ok", it.body.string())
        }
        assertEquals(1, solves.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed solve returns null to the caller and cools the host`() {
        val cooldown = CloudflareHostCooldown()
        val coordinator = CloudflareSolveCoordinator(cooldown)
        val solves = AtomicInteger()
        repeat(2) { server.enqueue(challenge()) }
        val client = clientWith(ClearanceSolver { solves.incrementAndGet(); false }, coordinator)
        repeat(2) {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("unsolved", it.header("X-Fallback"))
            }
        }
        // The second request hits the cooldown and starts no browser.
        assertEquals(1, solves.get())
        assertTrue(cooldown.isInCooldown(server.hostName))
    }

    @Test
    @Timeout(10)
    fun `a cancelled caller stops waiting for the solve`() {
        server.enqueue(challenge())
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val result = AtomicInteger(-1)
        val interceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            response.close()
            val thread = Thread { Thread.sleep(200); cancelled.set(true) }.apply { start() }
            val retried = chain.solveClearanceAndRetry(chain.request(), ClearanceSolver { awaitCancellation() },
                CloudflareSolveCoordinator(CloudflareHostCooldown())) { cancelled.get() }
            thread.join()
            result.set(if (retried == null) 0 else 1)
            response
        }
        OkHttpClient.Builder().addInterceptor(interceptor).build()
            .newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals(0, result.get())
        assertEquals(1, server.requestCount)
    }

    private fun clientWith(
        solver: ClearanceSolver,
        coordinator: CloudflareSolveCoordinator? = CloudflareSolveCoordinator(CloudflareHostCooldown()),
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isMihonCloudflareChallenge()) return@addInterceptor response
        response.close()
        chain.solveClearanceAndRetry(request, solver, coordinator)
            ?: response.newBuilder().header("X-Fallback", "unsolved").build()
    }.build()

    private fun challenge() = MockResponse().setResponseCode(403).setHeader("Server", "cloudflare").setBody("challenge")

}
