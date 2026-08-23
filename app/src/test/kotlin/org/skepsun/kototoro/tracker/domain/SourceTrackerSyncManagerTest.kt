package org.skepsun.kototoro.tracker.domain

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SourceTrackerSyncManagerTest {

    // ---------------------------------------------------------------- helpers

    private class FakeSource(override val name: String) : ContentSource {
        override val locale: String = ""
        override val contentType: ContentType = ContentType.MANGA
    }

    private fun content(id: Long, sourceKey: String): Content = Content(
        id = id,
        title = "Work $id",
        altTitles = emptySet(),
        url = "/work/$id",
        publicUrl = "https://example.org/work/$id",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = FakeSource(sourceKey),
    )

    private class FakeGate(
        var enabled: Boolean = true,
        var supportedSources: Set<String> = setOf("src1"),
    ) : SourceTrackerGate {
        override fun isEnabled(): Boolean = enabled
        override fun supports(content: Content): Boolean = supportsSource(content.source.name)
        override fun supportsSource(sourceKey: String): Boolean = sourceKey in supportedSources
    }

    private class FakeEmitter : SourceTrackerEventEmitter {
        // replay buffers emissions until the manager's collector registers on the lazy test
        // scheduler, so `emit(); awaitDrain()` is deterministic regardless of registration timing.
        private val _events = MutableSharedFlow<SourceTrackerEvent>(
            replay = 64,
            extraBufferCapacity = 64,
        )
        override val events: SharedFlow<SourceTrackerEvent> = _events.asSharedFlow()
        override fun emit(event: SourceTrackerEvent) {
            _events.tryEmit(event)
        }
    }

    private class RecordingManager(
        gate: SourceTrackerGate,
        emitter: SourceTrackerEventEmitter,
        scope: CoroutineScope,
        maxRetries: Int,
        syncTimeoutMs: Long,
        private val syncImpl: suspend (event: SourceTrackerEvent, attempt: Int) -> Boolean = { _, _ -> true },
    ) : SourceTrackerSyncManager(
        gate = gate,
        emitter = emitter,
        scope = scope,
        maxRetries = maxRetries,
        syncTimeoutMs = syncTimeoutMs,
        startImmediately = true,
    ) {
        val invocations = mutableListOf<SourceTrackerEvent>()
        val attemptsByContent = mutableMapOf<Long, Int>()
        val successes = mutableListOf<SourceTrackerEvent>()
        var maxConcurrent = 0
            private set
        private var concurrent = 0

        override suspend fun syncToTracker(event: SourceTrackerEvent): Boolean {
            concurrent++
            if (concurrent > maxConcurrent) {
                maxConcurrent = concurrent
            }
            try {
                val attempt = (attemptsByContent[event.contentId] ?: 0) + 1
                attemptsByContent[event.contentId] = attempt
                invocations += event
                val ok = syncImpl(event, attempt)
                if (ok) {
                    successes += event
                }
                return ok
            } finally {
                concurrent--
            }
        }
    }

    private fun TestScope.managerScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler = testScheduler))
    }

    private fun read(id: Long, source: String = "src1", percent: Float) = SourceTrackerEvent.Read(id, source, percent)
    private fun unread(id: Long, source: String = "src1") = SourceTrackerEvent.Unread(id, source)
    private fun fav(
        id: Long,
        source: String = "src1",
        added: Boolean = true,
    ) = SourceTrackerEvent.Favorite(id, source, added)
    private fun unfav(id: Long, source: String = "src1") = SourceTrackerEvent.Unfavorite(id, source)

    // ---------------------------------------------------------------- gate

    @Test
    fun `disabled gate drops every event with zero sync side effects`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(FakeGate(enabled = false), emitter, managerScope(), 3, 10_000)
        try {
            emitter.emit(read(1L, percent = 0.5f))
            emitter.emit(fav(2L))
            manager.awaitDrain()

            assertTrue(manager.invocations.isEmpty())
            assertNotNull(manager.lastError("src1", 1L))
        } finally {
            manager.onStop()
        }
    }

    @Test
    fun `unsupported source drops every event with zero sync side effects`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(
            FakeGate(enabled = true, supportedSources = emptySet()),
            emitter,
            managerScope(),
            3,
            10_000,
        )
        try {
            emitter.emit(read(1L, percent = 0.5f))
            manager.awaitDrain()

            assertTrue(manager.invocations.isEmpty())
            assertNotNull(manager.lastError("src1", 1L))
        } finally {
            manager.onStop()
        }
    }

    @Test
    fun `default gate is fully disabled`() {
        val gate = DefaultSourceTrackerGate()
        assertFalse(gate.isEnabled())
        assertFalse(gate.supportsSource("src1"))
        assertFalse(gate.supports(content(1L, "src1")))
    }

    @Test
    fun `gate supports delegates to the source key`() {
        val gate = FakeGate(enabled = true, supportedSources = setOf("src.manga"))
        assertTrue(gate.supportsSource("src.manga"))
        assertFalse(gate.supportsSource("src.other"))
        assertTrue(gate.supports(content(1L, "src.manga")))
        assertFalse(gate.supports(content(2L, "src.other")))
        assertTrue(gate.isEnabled())
        assertFalse(FakeGate(enabled = false).isEnabled())
    }

    // ---------------------------------------------------------------- folding

    @Test
    fun `foldLatest keeps only the newest read percent per content`() {
        assertEquals(
            listOf(read(1L, percent = 0.6f)),
            foldLatest(listOf(read(1L, percent = 0.3f), read(1L, percent = 0.6f), read(1L, percent = 0.4f))),
        )
    }

    @Test
    fun `foldLatest collapses read to unread and unread to read`() {
        assertEquals(
            listOf(unread(1L)),
            foldLatest(listOf(read(1L, percent = 0.5f), unread(1L))),
        )
        assertEquals(
            listOf(read(1L, percent = 0.2f)),
            foldLatest(listOf(unread(1L), read(1L, percent = 0.2f))),
        )
    }

    @Test
    fun `foldLatest collapses favorite to unfavorite and vice versa`() {
        assertEquals(
            listOf(unfav(1L)),
            foldLatest(listOf(fav(1L), unfav(1L))),
        )
        assertEquals(
            listOf(fav(1L, added = true)),
            foldLatest(listOf(unfav(1L), fav(1L, added = true))),
        )
        assertEquals(
            listOf(fav(1L, added = false)),
            foldLatest(listOf(fav(1L, added = true), fav(1L, added = false))),
        )
    }

    @Test
    fun `foldLatest merges independent axes preserving temporal order`() {
        assertEquals(
            listOf(read(1L, percent = 0.1f), unfav(1L)),
            foldLatest(
                listOf(
                    read(1L, percent = 0.1f),
                    fav(1L),
                    unfav(1L),
                ),
            ),
        )
    }

    @Test
    fun `foldLatest keeps distinct contents independent`() {
        assertEquals(
            listOf(read(1L, percent = 0.4f), read(2L, percent = 0.9f)),
            foldLatest(
                listOf(
                    read(1L, percent = 0.4f),
                    read(1L, percent = 0.2f),
                    read(2L, percent = 0.9f),
                ),
            ),
        )
        // Same contentId from different sources is NOT folded together.
        assertEquals(
            listOf(read(1L, source = "a", percent = 0.5f), read(1L, source = "b", percent = 0.6f)),
            foldLatest(listOf(read(1L, source = "a", percent = 0.5f), read(1L, source = "b", percent = 0.6f))),
        )
    }

    @Test
    fun `foldLatest is a no-op for small inputs`() {
        assertTrue(foldLatest(emptyList()).isEmpty())
        val single = read(1L, percent = 0.5f)
        assertEquals(listOf(single), foldLatest(listOf(single)))
    }

    // ---------------------------------------------------------------- end-to-end folding through the pipeline

    @Test
    fun `pipeline syncs only the folded latest state per content`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(FakeGate(), emitter, managerScope(), 3, 10_000)
        try {
            emitter.emit(read(1L, percent = 0.3f))
            emitter.emit(read(1L, percent = 0.6f))
            emitter.emit(read(1L, percent = 0.4f))
            emitter.emit(unread(1L))
            manager.awaitDrain()

            assertEquals(listOf(unread(1L)), manager.invocations)
            assertEquals(listOf(unread(1L)), manager.successes)
        } finally {
            manager.onStop()
        }
    }

    // ---------------------------------------------------------------- serial

    @Test
    fun `events for the same content are processed serially in arrival order`() = runTest {
        val emitter = FakeEmitter()
        val blocker = CompletableDeferred<Unit>()
        val manager = RecordingManager(FakeGate(), emitter, managerScope(), 3, 10_000) { event, _ ->
            if (event.contentId == 1L && event is SourceTrackerEvent.Read) {
                blocker.await()
            }
            true
        }
        try {
            emitter.emit(read(1L, percent = 0.5f))
            runCurrent() // register collector + start drain WITHOUT advancing virtual time
            // (advanceUntilIdle would fire withTimeout's 10s virtual timer and spuriously retry)
            // The first read is in flight (blocked); nothing else for content 1 can run yet.
            assertEquals(1, manager.invocations.size)

            // A second event arrives while the first is still in flight — it must queue behind it.
            emitter.emit(unread(1L))
            runCurrent()
            assertEquals(1, manager.invocations.size)

            blocker.complete(Unit)
            manager.awaitDrain()

            assertEquals(listOf(read(1L, percent = 0.5f), unread(1L)), manager.invocations)
            assertEquals(1, manager.maxConcurrent)
        } finally {
            manager.onStop()
        }
    }

    @Test
    fun `different contents are processed independently`() = runTest {
        val emitter = FakeEmitter()
        val blocker = CompletableDeferred<Unit>()
        val manager = RecordingManager(FakeGate(), emitter, managerScope(), 3, 10_000) { event, _ ->
            if (event.contentId == 1L) {
                blocker.await()
            }
            true
        }
        try {
            emitter.emit(read(1L, percent = 0.5f))
            emitter.emit(read(2L, percent = 0.9f))
            runCurrent() // register collector + start both drains without firing the 10s virtual timeout

            // Content 2 is not blocked by content 1.
            assertTrue(manager.invocations.any { it.contentId == 2L })
            assertEquals(2, manager.maxConcurrent)

            blocker.complete(Unit)
            manager.awaitDrain()
            assertEquals(2, manager.invocations.size)
        } finally {
            manager.onStop()
        }
    }

    // ---------------------------------------------------------------- retry

    @Test
    fun `failing sync retries with exponential backoff and eventually succeeds`() = runTest {
        val emitter = FakeEmitter()
        var failures = 2
        val manager = RecordingManager(
            FakeGate(),
            emitter,
            managerScope(),
            maxRetries = 3,
            syncTimeoutMs = 10_000,
        ) { _, attempt ->
            if (attempt <= failures) false else true
        }
        try {
            emitter.emit(read(1L, percent = 0.5f))
            manager.awaitDrain()

            assertEquals(3, manager.attemptsByContent[1L])
            assertEquals(1, manager.successes.size)
            assertEquals(listOf(read(1L, percent = 0.5f)), manager.successes)
            // Two failed attempts left a diagnostic, the final success cleared it.
            assertNull(manager.lastError("src1", 1L))
        } finally {
            manager.onStop()
        }
    }

    @Test
    fun `sync gives up after maxRetries and records the final error`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(
            FakeGate(),
            emitter,
            managerScope(),
            maxRetries = 2,
            syncTimeoutMs = 10_000,
        ) { _, _ ->
            false
        }
        try {
            emitter.emit(read(1L, percent = 0.5f))
            manager.awaitDrain()

            assertEquals(2, manager.attemptsByContent[1L])
            assertTrue(manager.successes.isEmpty())
            val error = manager.lastError("src1", 1L)
            assertNotNull(error)
            assertTrue(error!!.contains("give-up"))
        } finally {
            manager.onStop()
        }
    }

    @Test
    fun `backoff follows the exponential policy with a cap`() {
        assertEquals(1_000L, backoffDelayMillis(1))
        assertEquals(2_000L, backoffDelayMillis(2))
        assertEquals(4_000L, backoffDelayMillis(3))
        assertEquals(8_000L, backoffDelayMillis(4))
        assertEquals(8_000L, backoffDelayMillis(10))
    }

    // ---------------------------------------------------------------- timeout

    @Test
    fun `hanging sync times out, is recorded, and does not block other contents`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(
            FakeGate(),
            emitter,
            managerScope(),
            maxRetries = 2,
            syncTimeoutMs = 10_000,
        ) { event, _ ->
            if (event.contentId == 1L) {
                delay(1_000_000)
            }
            true
        }
        try {
            emitter.emit(read(1L, percent = 0.5f))
            emitter.emit(read(2L, percent = 0.9f))
            manager.awaitDrain()

            // Content 1 timed out on both attempts and gave up.
            assertEquals(2, manager.attemptsByContent[1L])
            assertFalse(manager.successes.any { it.contentId == 1L })
            val error = manager.lastError("src1", 1L)
            assertNotNull(error)
            assertTrue(error!!.contains("timeout"))

            // Content 2 was processed promptly — the timeout never blocked its queue.
            assertTrue(manager.successes.any { it.contentId == 2L })
            assertNull(manager.lastError("src1", 2L))
        } finally {
            manager.onStop()
        }
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun `cancellation stops processing new events cleanly`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(FakeGate(), emitter, managerScope(), 3, 10_000)
        try {
            emitter.emit(read(1L, percent = 0.5f))
            manager.awaitDrain()
            assertEquals(1, manager.invocations.size)

            manager.onStop()

            emitter.emit(read(2L, percent = 0.9f))
            emitter.emit(fav(3L))
            advanceUntilIdle()

            assertEquals(1, manager.invocations.size)
        } finally {
            manager.onStop()
        }
    }

    // ---------------------------------------------------------------- T4B.4 (no local rollback)

    @Test
    fun `sync failures never lose events nor affect the local result`() = runTest {
        val emitter = FakeEmitter()
        val manager = RecordingManager(
            FakeGate(),
            emitter,
            managerScope(),
            maxRetries = 1,
            syncTimeoutMs = 10_000,
        ) { _, _ ->
            false
        }
        try {
            // Mimic repository wiring: the local DB write commits first and returns true,
            // then the event is emitted. A failing sync afterwards must not change that.
            fun localWrite(ok: Boolean, event: SourceTrackerEvent): Boolean {
                if (ok) {
                    emitter.emit(event)
                }
                return ok
            }
            assertTrue(localWrite(true, read(1L, percent = 0.5f)))
            assertTrue(localWrite(true, fav(1L)))
            assertTrue(localWrite(true, read(2L, percent = 0.7f)))

            manager.awaitDrain()

            // Every folded event was still delivered and attempted despite the failures.
            assertEquals(
                listOf(
                    read(1L, percent = 0.5f),
                    fav(1L),
                    read(2L, percent = 0.7f),
                ),
                manager.invocations,
            )
            assertTrue(manager.successes.isEmpty())
        } finally {
            manager.onStop()
        }
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `diagnostics produce single-line copyable sanitized summaries`() {
        val event = SourceTrackerEvent.Read(
            contentId = 1L,
            sourceKey = "src1",
            percent = 0.5f,
            contentUrl = "https://user:pass@src.com/manga/1?token=abc&page=2",
        )

        val timeoutLine = SourceTrackerDiagnostics.timeout(event, attempt = 2, timeoutMs = 10_000)
        assertFalse(timeoutLine.contains('\n'))
        assertTrue(timeoutLine.contains("src1"))
        assertTrue(timeoutLine.contains("content=1"))
        assertTrue(timeoutLine.contains("attempt=2"))
        assertTrue(timeoutLine.contains("timeout"))
        assertTrue(timeoutLine.contains("limit=10000ms"))

        val dropLine = SourceTrackerDiagnostics.dropped(event, "gate")
        assertFalse(dropLine.contains('\n'))
        assertTrue(dropLine.contains("reason=gate"))
        // Sanitized: token value masked, userinfo stripped.
        assertTrue(dropLine.contains("token=***"))
        assertFalse(dropLine.contains("token=abc"))
        assertFalse(dropLine.contains("user:pass@"))

        val errorLine = SourceTrackerDiagnostics.classify(event, attempt = 1, error = IOException("boom"))
        assertFalse(errorLine.contains('\n'))
        assertTrue(errorLine.contains("[io]"))
        assertTrue(errorLine.contains("retryable"))
        assertTrue(errorLine.contains("attempt=1"))
        assertTrue(errorLine.contains("content=1"))

        val giveUpLine = SourceTrackerDiagnostics.giveUp(event, attempts = 3)
        assertFalse(giveUpLine.contains('\n'))
        assertTrue(giveUpLine.contains("after=3 attempts"))
    }
}
