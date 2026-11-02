package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class DesktopDownloadConcurrencyTest {
    @Test
    fun `the series cap admits exactly as many chapters as it allows`() = runBlocking<Unit> {
        val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val slots = DesktopDownloadSlots { 2 }
        coroutineScope {
            repeat(6) {
                launch {
                    slots.withSlot {
                        peak.getAndUpdate { current -> maxOf(current, inside.incrementAndGet()) }
                        delay(20)
                        inside.decrementAndGet()
                    }
                }
            }
        }
        assertEquals(2, peak.get())
        assertEquals(0, inside.get())
    }

    @Test
    fun `no limit admits every chapter at once`() = runBlocking<Unit> {
        val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val everyone = CompletableDeferred<Unit>()
        val slots = DesktopDownloadSlots { null }
        coroutineScope {
            repeat(4) {
                launch {
                    slots.withSlot {
                        peak.getAndUpdate { current -> maxOf(current, inside.incrementAndGet()) }
                        everyone.await()
                        inside.decrementAndGet()
                    }
                }
            }
            withTimeout(2000) {
                while (inside.get() < 4) delay(5)
            }
            everyone.complete(Unit)
        }
        assertEquals(4, peak.get())
    }

    @Test
    fun `cancelling a waiting chapter does not lose the permit it never took`() = runBlocking<Unit> {
        val slots = DesktopDownloadSlots { 1 }
        val holding = CompletableDeferred<Unit>()
        val holder = launch { slots.withSlot { holding.await() } }
        val waiting = launch { slots.withSlot { error("a cancelled waiter must not run") } }
        delay(50)
        waiting.cancel()
        holding.complete(Unit)
        holder.join()
        // The slot is free again: a later chapter gets through instead of waiting forever.
        withTimeout(2000) { slots.withSlot { } }
    }

    @Test
    fun `requests to one series are spaced while another series is not delayed`() = runBlocking<Unit> {
        val pacer = DesktopRequestPacer(delayMs = { 120L })
        pacer.await(1L)
        val startedAt = System.currentTimeMillis()
        pacer.await(1L)
        val spaced = System.currentTimeMillis() - startedAt
        val otherStartedAt = System.currentTimeMillis()
        pacer.await(2L)
        val otherSpent = System.currentTimeMillis() - otherStartedAt
        assertTrue(spaced >= 80L, "expected the second request of the same series to wait, waited $spaced ms")
        assertTrue(otherSpent < 80L, "another series must not inherit the spacing, waited $otherSpent ms")
    }

    @Test
    fun `a zero delay turns the spacing off`() = runBlocking<Unit> {
        val pacer = DesktopRequestPacer(delayMs = { 0L })
        val startedAt = System.currentTimeMillis()
        repeat(5) { pacer.await(1L) }
        assertTrue(System.currentTimeMillis() - startedAt < 80L)
    }
}
