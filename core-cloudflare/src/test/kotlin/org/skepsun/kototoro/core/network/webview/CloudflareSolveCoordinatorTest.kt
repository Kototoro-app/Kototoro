package org.skepsun.kototoro.core.network.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.network.CloudflareHostCooldown

/**
 * Behavioural tests for [CloudflareSolveCoordinator] using a fake solve action — no real
 * WebView or OkHttp is involved. Verify the host-level constraints the WebView batch promises:
 *  - at most one in-flight solve per host;
 *  - concurrent waiters share the same solve result (cookie reuse on success);
 *  - a failed solve cools the host (no new WebView during the cooldown window);
 *  - a cancelled waiter leaves the shared solve untouched, but cancelling ALL waiters stops
 *    the in-flight solve (destroying its WebView analog).
 */
class CloudflareSolveCoordinatorTest {

    private fun newCoordinator(
        hostCooldown: CloudflareHostCooldown = CloudflareHostCooldown(),
    ) = CloudflareSolveCoordinator(hostCooldown)

    @Test
    fun `concurrent calls for the same host share a single in-flight solve`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = newCoordinator()
            val started = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            var actionCalls = 0
            val action: suspend () -> Boolean = {
                actionCalls++
                started.send(Unit)
                release.await()
                true
            }

            val results = (1..3).map {
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.solve(HOST, action) }
            }
            // Wait until the single driver of the solve is inside the action.
            started.receive()
            // While the solve is in flight no other waiter may launch a second solve.
            assertEquals(1, actionCalls)

            release.complete(Unit)
            assertEquals(listOf(true, true, true), results.map { it.await() })
            assertEquals(1, actionCalls, "shared success must not re-solve for waiters")
        }
    }

    @Test
    fun `concurrent waiters share a failed result and cool the host once`() = runBlocking {
        withTimeout(10_000) {
            val now = java.util.concurrent.atomic.AtomicLong(0L)
            val hostCooldown = CloudflareHostCooldown().apply {
                cooldownMillis = 10_000L
                nowMillis = { now.get() }
            }
            val coordinator = newCoordinator(hostCooldown)
            var actionCalls = 0
            val action: suspend () -> Boolean = {
                actionCalls++
                false
            }

            val results = (1..3).map { async { coordinator.solve(HOST, action) } }.map { it.await() }
            assertEquals(listOf(false, false, false), results)
            assertEquals(1, actionCalls)
            assertTrue(hostCooldown.isInCooldown(HOST))

            // During the cooldown window new solve attempts are skipped without touching the solver.
            assertFalse(coordinator.solve(HOST, action))
            assertEquals(1, actionCalls)

            // After the cooldown expires the same host is attempted again and can succeed.
            now.set(10_001L)
            assertTrue(coordinator.solve(HOST) { actionCalls++; true })
            assertEquals(2, actionCalls)
        }
    }

    @Test
    fun `cancelling one waiter does not abort the shared solve`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = newCoordinator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var actionCalls = 0
            val action: suspend () -> Boolean = {
                actionCalls++
                started.complete(Unit)
                release.await()
                true
            }

            val waiter1 = async(start = CoroutineStart.UNDISPATCHED) { coordinator.solve(HOST, action) }
            started.await()
            // Attach a second waiter while the solve is still in flight.
            val waiter2 = async(start = CoroutineStart.UNDISPATCHED) { coordinator.solve(HOST, action) }
            // With another waiter still interested the solve must survive waiter1's cancellation.
            waiter1.cancelAndJoin()
            release.complete(Unit)
            assertTrue(waiter2.await())
            assertEquals(1, actionCalls)
        }
    }

    @Test
    fun `cancelling the last waiter cancels the in-flight solve and a later call starts fresh`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = newCoordinator()
            val started = CompletableDeferred<Unit>()
            val solveCancelled = CompletableDeferred<Boolean>()
            var actionCalls = 0
            val action: suspend () -> Boolean = {
                actionCalls++
                started.complete(Unit)
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { solveCancelled.complete(true) }
                    // never resumed by itself: waits for coordinator cancellation (WebView destroy path)
                }
                true
            }

            val job = launch { coordinator.solve(HOST, action) }
            started.await()
            job.cancelAndJoin()
            assertTrue(
                solveCancelled.await(),
                "in-flight solve should be cancelled when its last waiter disappears",
            )

            // Cancellation must not poison the host: a subsequent call starts a brand new solve.
            assertTrue(coordinator.solve(HOST) { actionCalls++; true })
            assertEquals(2, actionCalls)
        }
    }

    @Test
    fun `different hosts solve independently and concurrently`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = newCoordinator()
            val hostAStarted = CompletableDeferred<Unit>()
            val hostBStarted = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val releaseB = CompletableDeferred<Unit>()
            val actionA: suspend () -> Boolean = {
                hostAStarted.complete(Unit)
                releaseA.await()
                true
            }
            val actionB: suspend () -> Boolean = {
                hostBStarted.complete(Unit)
                releaseB.await()
                true
            }

            val jobA = async { coordinator.solve("host-a.test", actionA) }
            val jobB = async { coordinator.solve("host-b.test", actionB) }
            hostAStarted.await()
            hostBStarted.await()
            releaseA.complete(Unit)
            releaseB.complete(Unit)
            assertTrue(jobA.await())
            assertTrue(jobB.await())
        }
    }

    private companion object {
        const val HOST = "cdn.example.com"
    }
}
