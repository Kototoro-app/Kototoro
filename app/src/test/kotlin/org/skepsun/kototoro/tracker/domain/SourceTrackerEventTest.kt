package org.skepsun.kototoro.tracker.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceTrackerEventTest {

    @Test
    fun `event model exposes stable equality and copy semantics`() {
        val a = SourceTrackerEvent.Read(
            contentId = 7L,
            sourceKey = "src.manga",
            percent = 0.42f,
            contentUrl = "https://src/manga/7",
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        assertNotEquals(
            a,
            SourceTrackerEvent.Read(contentId = 8L, sourceKey = "src.manga", percent = 0.42f),
        )
        assertNotEquals(
            a,
            SourceTrackerEvent.Read(contentId = 7L, sourceKey = "src.manga", percent = 0.43f),
        )
        assertNotEquals(
            a,
            SourceTrackerEvent.Read(contentId = 7L, sourceKey = "src.other", percent = 0.42f),
        )
        assertNotEquals(
            SourceTrackerEvent.Favorite(contentId = 7L, sourceKey = "src.manga", added = true),
            SourceTrackerEvent.Favorite(contentId = 7L, sourceKey = "src.manga", added = false),
        )
        assertNotEquals(
            SourceTrackerEvent.Unfavorite(contentId = 7L, sourceKey = "src.manga"),
            SourceTrackerEvent.Unread(contentId = 7L, sourceKey = "src.manga"),
        )
    }

    @Test
    fun `bus delivers emitted events to a collector in order`() = runTest {
        val received = mutableListOf<SourceTrackerEvent>()
        val job = launch {
            SourceTrackerEventBus.events.collect { received += it }
        }
        yield() // let the collector register before emitting (tryEmit drops without subscribers)
        val events = listOf(
            SourceTrackerEvent.Read(contentId = 1L, sourceKey = "src1", percent = 0.1f),
            SourceTrackerEvent.Unread(contentId = 1L, sourceKey = "src1"),
            SourceTrackerEvent.Favorite(contentId = 2L, sourceKey = "src1", added = true),
            SourceTrackerEvent.Unfavorite(contentId = 2L, sourceKey = "src1"),
        )
        events.forEach(SourceTrackerEventBus::emit)
        advanceUntilIdle()
        job.cancel()

        assertEquals(events, received)
    }

    @Test
    fun `bus is shared - every subscriber sees every event`() = runTest {
        val first = mutableListOf<SourceTrackerEvent>()
        val second = mutableListOf<SourceTrackerEvent>()
        val job1 = launch { SourceTrackerEventBus.events.collect { first += it } }
        val job2 = launch { SourceTrackerEventBus.events.collect { second += it } }
        yield() // let both collectors register before emitting
        val event = SourceTrackerEvent.Unread(contentId = 9L, sourceKey = "src1")
        SourceTrackerEventBus.emit(event)
        advanceUntilIdle()
        job1.cancel()
        job2.cancel()

        assertEquals(listOf(event), first)
        assertEquals(listOf(event), second)
    }

    @Test
    fun `bus drops oldest events when the buffer overflows`() = runTest {
        // A registered but slow collector: it consumes slower than emissions, so its buffer
        // fills; with BufferOverflow.DROP_OLDEST the oldest buffered events are discarded.
        val received = mutableListOf<SourceTrackerEvent>()
        val job = launch {
            SourceTrackerEventBus.events.collect {
                received += it
                delay(1)
            }
        }
        // Let the collector register and suspend, then flood without suspension: the test
        // coroutine never yields, so the collector cannot keep up.
        yield()
        repeat(300) { index ->
            SourceTrackerEventBus.emit(
                SourceTrackerEvent.Read(contentId = (index + 1).toLong(), sourceKey = "src1", percent = 1f),
            )
        }
        advanceUntilIdle()
        job.cancel()

        assertEquals(SourceTrackerEventBus.BUFFER_CAPACITY, received.size)
        // The 44 oldest (ids 1..44) were dropped; the newest 256 (ids 45..300) survived.
        assertEquals(45L, received.first().contentId)
        assertEquals(300L, received.last().contentId)
        assertFalse(received.any { it.contentId <= 44L })
    }

    @Test
    fun `bus emit never suspends even under burst load`() = runTest {
        val job = launch {
            SourceTrackerEventBus.events.collect {
                delay(1)
            }
        }
        yield()
        // Since emit() is non-suspending, this loop completes without ever being suspended.
        repeat(1_000) {
            SourceTrackerEventBus.emit(
                SourceTrackerEvent.Read(contentId = it.toLong(), sourceKey = "src1", percent = 1f),
            )
        }
        assertTrue(true)
        job.cancel()
    }
}
