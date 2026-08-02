package org.skepsun.kototoro.core.image

import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ImageFailureSuppressingInterceptorTest {

	@Test
	fun `concurrent cover requests with the same URL both proceed`() = runTest {
		val request = mockk<ImageRequest> {
			every { data } returns COVER_URL
			every { memoryCacheKey } returns "shared-cover#source#owner#$COVER_URL#home_hero_cover"
			every { diskCacheKey } returns "shared-cover#source#owner#$COVER_URL"
		}
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

	private companion object {
		private const val COVER_URL = "https://example.com/cover.jpg"
	}
}
