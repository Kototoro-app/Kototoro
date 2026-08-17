package org.skepsun.kototoro.core.exceptions.resolve

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CloudFlareSingleFlightTest {

    @Test
    fun `concurrent requests for same host share one resolution`() = runTest {
        val singleFlight = CloudFlareSingleFlight()
        val resolutionStarted = CompletableDeferred<Unit>()
        val finishResolution = CompletableDeferred<Unit>()
        val invocationCount = AtomicInteger()

        val first = async {
            singleFlight.run("example.org") {
                invocationCount.incrementAndGet()
                resolutionStarted.complete(Unit)
                finishResolution.await()
                true
            }
        }
        resolutionStarted.await()
        val second = async {
            singleFlight.run("example.org") {
                invocationCount.incrementAndGet()
                false
            }
        }
        yield()
        finishResolution.complete(Unit)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, invocationCount.get())
    }

    @Test
    fun `different hosts resolve independently`() = runTest {
        val singleFlight = CloudFlareSingleFlight()
        val invocationCount = AtomicInteger()

        val first = async {
            singleFlight.run("a.example") {
                invocationCount.incrementAndGet()
                true
            }
        }
        val second = async {
            singleFlight.run("b.example") {
                invocationCount.incrementAndGet()
                true
            }
        }

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(2, invocationCount.get())
    }
}
