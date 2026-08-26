package org.skepsun.kototoro.core.image

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.CloudflareHostCooldown
import org.skepsun.kototoro.core.util.ext.bypassFailureCooldownKey

class ImageFailureSuppressingInterceptorTest {

    @Test
    fun `concurrent cover requests with the same URL both proceed`() = runTest {
        val request = coverRequest(COVER_URL)
        val success = mockk<SuccessResult>()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val firstChain = mockk<Interceptor.Chain> {
            every { this@mockk.request } returns request
            coEvery { proceed() } coAnswers {
                firstRequestStarted.complete(Unit)
                releaseFirstRequest.await()
                success
            }
        }
        val secondChain = mockk<Interceptor.Chain> {
            every { this@mockk.request } returns request
            coEvery { proceed() } returns success
        }
        val interceptor = ImageFailureSuppressingInterceptor()

        val firstResult = async { interceptor.intercept(firstChain) }
        firstRequestStarted.await()
        val secondResult = async { interceptor.intercept(secondChain) }

        assertSame(success, secondResult.await())
        releaseFirstRequest.complete(Unit)
        assertSame(success, firstResult.await())
        coVerify(exactly = 1) { firstChain.proceed() }
        coVerify(exactly = 1) { secondChain.proceed() }
    }

    @Test
    fun `ordinary 5xx does not suppress a later cover retry`() = runTest {
        val cooldown = CloudflareHostCooldown()
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL)

        val failingChain = chain(
            request,
            failing = ErrorResult(
                image = null,
                request = request,
                throwable = httpError(COVER_URL, code = 500, message = "Internal Server Error"),
            ),
        )
        assertTrue(interceptor.intercept(failingChain) is ErrorResult)
        // A transient server error must not cool the host nor negatively cache the URL.
        assertFalse(cooldown.isInCooldown(HOST))

        // The same cover is attempted again and succeeds once the network/server recovers.
        val success = mockk<SuccessResult>()
        val retryChain = chain(request, result = success)
        assertSame(success, interceptor.intercept(retryChain))
        coVerify(exactly = 1) { retryChain.proceed() }
    }

    @Test
    fun `cloudflare 403 on a cover cools the host`() = runTest {
        val cooldown = CloudflareHostCooldown()
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL)

        val failingChain = chain(
            request,
            failing = ErrorResult(
                image = null,
                request = request,
                throwable = cloudflare403(COVER_URL),
            ),
        )
        assertTrue(interceptor.intercept(failingChain) is ErrorResult)
        assertTrue(cooldown.isInCooldown(HOST))
    }

    @Test
    fun `plain 403 without cloudflare does not cool the host`() = runTest {
        val cooldown = CloudflareHostCooldown()
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL)

        val failingChain = chain(
            request,
            failing = ErrorResult(
                image = null,
                request = request,
                throwable = httpError(COVER_URL, code = 403, message = "Forbidden"),
            ),
        )
        assertTrue(interceptor.intercept(failingChain) is ErrorResult)
        assertFalse(cooldown.isInCooldown(HOST))
    }

    @Test
    fun `cover request for a cooled host is short circuited without touching the network`() = runTest {
        val cooldown = CloudflareHostCooldown().apply { coolDown(HOST) }
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL)

        val chain = chain(request, result = mockk<SuccessResult>())
        val result = interceptor.intercept(chain)

        assertTrue(result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is SuppressedImageRequestException)
        coVerify(exactly = 0) { chain.proceed() }
    }

    @Test
    fun `non-cover request for a cooled host is not suppressed`() = runTest {
        val cooldown = CloudflareHostCooldown().apply { coolDown(HOST) }
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL, cover = false)
        val success = mockk<SuccessResult>()

        val chain = chain(request, result = success)
        assertSame(success, interceptor.intercept(chain))
        coVerify(exactly = 1) { chain.proceed() }
    }

    @Test
    fun `user refresh bypass skips the host cooldown`() = runTest {
        val cooldown = CloudflareHostCooldown().apply { coolDown(HOST) }
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL, bypass = true)
        val success = mockk<SuccessResult>()

        val chain = chain(request, result = success)
        assertSame(success, interceptor.intercept(chain))
        coVerify(exactly = 1) { chain.proceed() }
    }

    @Test
    fun `after host cooldown expires the same cover can succeed again`() = runTest {
        var now = 0L
        val cooldown = CloudflareHostCooldown().apply { nowMillis = { now } }
        val interceptor = ImageFailureSuppressingInterceptor(cooldown)
        val request = coverRequest(COVER_URL)

        // The host fails once with a Cloudflare challenge at t=1000.
        now = 1_000L
        val failingChain = chain(
            request,
            failing = ErrorResult(
                image = null,
                request = request,
                throwable = cloudflare403(COVER_URL),
            ),
        )
        assertTrue(interceptor.intercept(failingChain) is ErrorResult)
        assertTrue(cooldown.isInCooldown(HOST))

        // While the cooldown window is active a new cover request is skipped.
        now = 10_000L
        val suppressedChain = chain(request, result = mockk<SuccessResult>())
        assertTrue(interceptor.intercept(suppressedChain) is ErrorResult)
        coVerify(exactly = 0) { suppressedChain.proceed() }

        // Once the network recovers and the cooldown expires the same cover succeeds.
        now = cooldown.cooldownMillis + 1_001L
        val success = mockk<SuccessResult>()
        val retryChain = chain(request, result = success)
        assertSame(success, interceptor.intercept(retryChain))
        coVerify(exactly = 1) { retryChain.proceed() }
    }

    @Test
    fun `blank image data is short circuited without touching the network`() = runTest {
        val interceptor = ImageFailureSuppressingInterceptor()
        val request = mockk<ImageRequest> {
            every { data } returns "   "
            every { memoryCacheKey } returns null
            every { diskCacheKey } returns null
            every { extras } returns Extras.EMPTY
            every { error() } returns null
        }
        val chain = chain(request, result = mockk<SuccessResult>())

        val result = interceptor.intercept(chain)
        assertTrue(result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is SuppressedImageRequestException)
        coVerify(exactly = 0) { chain.proceed() }
    }

    @Test
    fun `missing local app cache file is short circuited without touching the network`() = runTest {
        val interceptor = ImageFailureSuppressingInterceptor()
        val request = mockk<ImageRequest> {
            every { data } returns "file:///definitely/missing-cover.jpg"
            every { memoryCacheKey } returns "shared-cover#source#owner#file%3A%2F%2F#home_hero_cover"
            every { diskCacheKey } returns "shared-cover#source#owner#file%3A%2F%2F"
            every { extras } returns Extras.EMPTY
            every { error() } returns null
        }
        val chain = chain(request, result = mockk<SuccessResult>())

        val result = interceptor.intercept(chain)
        assertTrue(result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is SuppressedImageRequestException)
        coVerify(exactly = 0) { chain.proceed() }
    }

    // --- helpers -------------------------------------------------------------

    private fun coverRequest(url: String, cover: Boolean = true, bypass: Boolean = false): ImageRequest = mockk {
        every { data } returns if (cover) url else "https://cdn.example.com/page/42.jpg"
        every { memoryCacheKey } returns if (cover) "shared-cover#source#owner#$url#home_hero_cover" else "page#$url"
        every { diskCacheKey } returns if (cover) "shared-cover#source#owner#$url" else "page#$url"
        every { extras } returns if (bypass) {
            Extras.Builder().set(bypassFailureCooldownKey, true).build()
        } else {
            Extras.EMPTY
        }
        every { error() } returns null
    }

    private fun chain(
        request: ImageRequest,
        result: SuccessResult? = null,
        failing: ErrorResult? = null,
    ): Interceptor.Chain {
        require((result == null) != (failing == null)) { "exactly one of result/failing must be set" }
        val answer = checkNotNull(result ?: failing)
        return mockk {
            every { this@mockk.request } returns request
            coEvery { proceed() } returns answer
        }
    }

    private fun cloudflare403(url: String): HttpException = httpError(
        url = url,
        code = 403,
        message = "CloudFlare Protected CDN",
    )

    private fun httpError(url: String, code: Int, message: String): HttpException = HttpException(
        NetworkResponse(
            code = code,
            requestMillis = 0L,
            responseMillis = 0L,
            headers = NetworkHeaders.Builder().build(),
            body = null,
            delegate = Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .message(message)
                .code(code)
                .build(),
        ),
    )

    private companion object {
        private const val COVER_URL = "https://example.com/cover.jpg"
        private const val HOST = "example.com"
    }
}
