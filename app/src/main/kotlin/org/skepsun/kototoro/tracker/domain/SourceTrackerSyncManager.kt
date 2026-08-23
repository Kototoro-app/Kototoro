package org.skepsun.kototoro.tracker.domain

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.skepsun.kototoro.extensions.recovery.SourceRefreshDiagnostics
import org.skepsun.kototoro.parsers.model.Content

/**
 * SourceTracker sync gate (plan T4B.2, "supports/设置检查").
 *
 * Two independent checks, both required for an event to reach the network:
 *  - [isEnabled] — master switch; when off, zero network side effects;
 *  - [supports] / [supportsSource] — per-source capability flag.
 *
 * No built-in content source exposes a SourceTracker capability flag yet (the ABI is wired
 * in later phases), so the default gate answers `false` for both: events are dropped. This is
 * the safe default the plan requires ("设置关闭和 supports=false 时零网络副作用").
 */
interface SourceTrackerGate {

    /** Global master switch. Defaults to `false` until a real setting is wired (Phase 5+). */
    fun isEnabled(): Boolean

    /** Per-content capability check (concrete content) — delegates to [supportsSource]. */
    fun supports(content: Content): Boolean

    /** Per-source capability check, keyed by the canonical source key (`ContentSource.name`). */
    fun supportsSource(sourceKey: String): Boolean
}

/**
 * Default gate: everything off. Configurable/capability-driven implementations are expected
 * to replace it in Phase 5+ (e.g. reading a dedicated `prefs` switch and a per-source flag).
 */
@Singleton
class DefaultSourceTrackerGate @Inject constructor() : SourceTrackerGate {

    override fun isEnabled(): Boolean = false

    override fun supports(content: Content): Boolean = supportsSource(content.source.name)

    override fun supportsSource(sourceKey: String): Boolean = false
}

/**
 * Pure, single-line, copyable diagnostics for SourceTracker sync (plan T4B.3).
 *
 * No Android dependencies (JVM-unit-testable). Reuses [SourceRefreshDiagnostics] so every
 * output line is sanitized (URL userinfo stripped, sensitive query values masked) and
 * collapsed onto one line.
 */
object SourceTrackerDiagnostics {

    private const val PHASE = "sourcetracker"

    private fun urlOf(event: SourceTrackerEvent): String? = event.contentUrl?.takeIf { it.isNotBlank() }

    /** Gate rejection: event was dropped before any sync attempt. */
    fun dropped(event: SourceTrackerEvent, reason: String): String {
        val url = urlOf(event)
        val message = buildString {
            append("drop content=")
            append(event.contentId)
            append(" reason=")
            append(reason)
            if (url != null) {
                append(" url=")
                append(SourceRefreshDiagnostics.sanitizeUrl(url))
            }
        }
        return SourceRefreshDiagnostics.summary(event.sourceKey, null, PHASE, message)
    }

    /** A single attempt hit the per-attempt timeout. */
    fun timeout(event: SourceTrackerEvent, attempt: Int, timeoutMs: Long): String {
        return SourceRefreshDiagnostics.summary(
            event.sourceKey,
            null,
            PHASE,
            "timeout content=${event.contentId} attempt=$attempt limit=${timeoutMs}ms",
        )
    }

    /** Failure classified from a [Throwable]; uses the shared [SourceRefreshDiagnostics.classify]. */
    fun classify(event: SourceTrackerEvent, attempt: Int, error: Throwable): String {
        val base = SourceRefreshDiagnostics.classify(event.sourceKey, PHASE, error)
        val url = urlOf(event)
        return buildString {
            append(base)
            append(" content=")
            append(event.contentId)
            append(" attempt=")
            append(attempt)
            if (url != null) {
                append(" url=")
                append(SourceRefreshDiagnostics.sanitizeUrl(url))
            }
        }
    }

    /** Final failure after all retries were exhausted. */
    fun giveUp(event: SourceTrackerEvent, attempts: Int): String {
        return SourceRefreshDiagnostics.summary(
            event.sourceKey,
            null,
            PHASE,
            "give-up content=${event.contentId} after=$attempts attempts",
        )
    }
}

/**
 * Folds a burst of events for the SAME content into the latest state per axis (plan T4B.2,
 * "最新状态折叠"). Pure function — no I/O, unit-testable.
 *
 * Rules, applied per (sourceKey, contentId) group in first-appearance order:
 *  - several `Read`s → keep only the Read with the largest [SourceTrackerEvent.Read.percent];
 *  - a `Read` that is superseded by a later `Unread` → keep only the `Unread`;
 *  - an `Unread` superseded by a later `Read` → keep only the `Read`;
 *  - the favorite axis is last-wins across [SourceTrackerEvent.Favorite] /
 *    [SourceTrackerEvent.Unfavorite];
 *  - reading and favorite results for one content are ordered by the index of their final
 *    occurrence, preserving the temporal order between the two axes.
 *
 * Independent contents never influence each other.
 */
internal fun foldLatest(events: List<SourceTrackerEvent>): List<SourceTrackerEvent> {
    if (events.size < 2) {
        return events
    }
    data class AxisState(
        val reading: SourceTrackerEvent? = null,
        val readingLast: Int = -1,
        val favorite: SourceTrackerEvent? = null,
        val favoriteLast: Int = -1,
    )
    val states = LinkedHashMap<ContentKey, AxisState>()
    events.forEachIndexed { index, event ->
        val key = ContentKey(event.sourceKey, event.contentId)
        val state = states[key] ?: AxisState()
        states[key] = when (event) {
            is SourceTrackerEvent.Read -> {
                val currentReading = state.reading
                val keepMax = currentReading is SourceTrackerEvent.Read &&
                    currentReading.percent >= event.percent
                state.copy(
                    reading = if (keepMax) state.reading else event,
                    readingLast = index,
                )
            }

            is SourceTrackerEvent.Unread -> state.copy(reading = event, readingLast = index)

            is SourceTrackerEvent.Favorite,
            is SourceTrackerEvent.Unfavorite -> state.copy(favorite = event, favoriteLast = index)
        }
    }
    return states.flatMap { (_, state) ->
        buildList {
            if (state.reading != null) {
                add(state.reading to state.readingLast)
            }
            if (state.favorite != null) {
                add(state.favorite to state.favoriteLast)
            }
        }.sortedBy { it.second }.map { it.first }
    }
}

/**
 * Composite content key: source-local ids are NOT unique across sources, so serialization and
 * fold grouping are keyed by sourceKey + contentId.
 */
internal data class ContentKey(
    val sourceKey: String,
    val contentId: Long,
)

/**
 * Consumes [SourceTrackerEventBus] events and performs the website sync (plan T4B.2/T4B.3).
 *
 * Pipeline guarantees:
 *  - Gate: disabled or unsupported events are dropped with zero network side effects.
 *  - Serial per content: events for one (sourceKey, contentId) are processed by a single
 *    worker coroutine, in arrival order; concurrent bursts are folded by [foldLatest].
 *  - Finite retry: at most [maxRetries] attempts per event with exponential backoff
 *    (`2^n * 500ms`, capped at 8s).
 *  - Timeout: every attempt is bounded by [syncTimeoutMs]; timeouts are recorded and the
 *    queue keeps moving.
 *  - Cancellation: all work is launched in [scope]; [onStop] cancels it (structured).
 *  - Diagnostics: per-content [lastError] single-line summaries via [SourceTrackerDiagnostics].
 *
 * `syncToTracker` is a placeholder until Phase 5 wires the real SourceTracker HTTP/extension
 * call: it deliberately performs NO network I/O and reports success. Tests override it.
 */
@Singleton
open class SourceTrackerSyncManager @Inject constructor(
    private val gate: SourceTrackerGate,
) : SourceTrackerEventEmitter {

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val MAX_RETRIES = 8
        const val DEFAULT_SYNC_TIMEOUT_MS = 10_000L
        const val BACKOFF_BASE_MS = 500L
        const val BACKOFF_CAP_MS = 8_000L
    }

    /** Ingestion pipeline scope. Production uses Default; tests inject the test scheduler. */
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Event source. Defaults to the shared bus; the test constructor injects a private one. */
    private var emitter: SourceTrackerEventEmitter = SourceTrackerEventBus

    private var maxRetries: Int = DEFAULT_MAX_RETRIES
    private var syncTimeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Guarded by [stateLock]. Workers for currently-draining content keys. */
    private val stateLock = Any()
    private val pendingByContent = HashMap<ContentKey, MutableList<SourceTrackerEvent>>()
    private val workers = HashSet<ContentKey>()

    /** Bounded-duration work currently running inside a drain (for [awaitDrain]). */
    private val inFlightSyncs = AtomicInteger(0)

    private val lastErrorByContent = ConcurrentHashMap<ContentKey, String>()

    /**
     * Test/alternate construction: process events from a private [emitter] on a caller-owned
     * [scope] (e.g. the `runTest` scheduler for virtual time).
     */
    internal constructor(
        gate: SourceTrackerGate,
        emitter: SourceTrackerEventEmitter,
        scope: CoroutineScope,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        syncTimeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS,
        startImmediately: Boolean = true,
    ) : this(gate) {
        this.emitter = emitter
        this.scope = scope
        this.maxRetries = maxRetries.coerceIn(0, MAX_RETRIES)
        this.syncTimeoutMs = syncTimeoutMs.coerceAtLeast(1L)
        if (startImmediately) {
            start()
        }
    }

    /** Subscribes to [emitter]; idempotent. Invoked automatically in production by the DI seam. */
    fun start() {
        if (stopped.get() || !started.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            emitter.events.collect { event -> onEvent(event) }
        }
    }

    /** Cancels the pipeline scope and stops accepting work (structured cancellation). */
    fun onStop() {
        if (stopped.compareAndSet(false, true)) {
            scope.cancel()
        }
    }

    /**
     * Performs the actual website sync for [event]. Phase 5+ wires the real SourceTracker
     * HTTP/extension call; until then this is a no-op placeholder that reports success —
     * deliberately ZERO network side effects. Override in tests to simulate failures,
     * retries and timeouts.
     */
    open suspend fun syncToTracker(event: SourceTrackerEvent): Boolean = true

    /** Latest diagnostic for a content, or null when the last attempt succeeded. */
    fun lastError(sourceKey: String, contentId: Long): String? {
        return lastErrorByContent[ContentKey(sourceKey, contentId)]
    }

    /** Snapshot of all per-content last errors (content key → diagnostic line). */
    fun lastErrorsSnapshot(): Map<String, String> = lastErrorByContent.entries
        .associate { (key, error) -> "${key.sourceKey}|${key.contentId}" to error }

    /**
     * Suspends until the pipeline has drained everything currently queued. Mainly a test tool
     * (with virtual time), also useful for orderly shutdown before [onStop]. The initial
     * [kotlinx.coroutines.yield] lets the collector consume events already buffered in the
     * emitter before the idle check, so `emit(); awaitDrain()` is well-defined.
     */
    suspend fun awaitDrain() {
        yield()
        while (true) {
            val idle = synchronized(stateLock) {
                workers.isEmpty() && pendingByContent.values.all { it.isEmpty() } && inFlightSyncs.get() == 0
            }
            if (idle) {
                return
            }
            delay(10)
        }
    }

    override fun emit(event: SourceTrackerEvent) {
        emitter.emit(event)
    }

    override val events: SharedFlow<SourceTrackerEvent>
        get() = emitter.events

    internal fun onEvent(event: SourceTrackerEvent) {
        if (!gate.isEnabled() || !gate.supportsSource(event.sourceKey)) {
            val key = ContentKey(event.sourceKey, event.contentId)
            lastErrorByContent[key] = SourceTrackerDiagnostics.dropped(event, "gate")
            return
        }
        val key = ContentKey(event.sourceKey, event.contentId)
        var spawn = false
        synchronized(stateLock) {
            pendingByContent.getOrPut(key) { mutableListOf() }.add(event)
            spawn = workers.add(key)
        }
        if (spawn) {
            scope.launch { drain(key) }
        }
    }

    private suspend fun drain(key: ContentKey) {
        try {
            while (true) {
                val batch = synchronized(stateLock) {
                    val pending = pendingByContent[key] ?: return@synchronized null
                    if (pending.isEmpty()) {
                        null
                    } else {
                        val snapshot = pending.toList()
                        pending.clear()
                        snapshot
                    }
                } ?: break
                inFlightSyncs.incrementAndGet()
                try {
                    for (event in foldLatest(batch)) {
                        syncWithRetry(event)
                    }
                } finally {
                    inFlightSyncs.decrementAndGet()
                }
            }
        } finally {
            var relaunch = false
            synchronized(stateLock) {
                workers.remove(key)
                val pending = pendingByContent[key]
                if (pending.isNullOrEmpty()) {
                    pendingByContent.remove(key)
                } else {
                    // Events arrived while draining: reclaim the worker slot so the drain
                    // restarts and no event is orphaned.
                    workers.add(key)
                    relaunch = true
                }
            }
            if (relaunch) {
                scope.launch { drain(key) }
            }
        }
    }

    private suspend fun syncWithRetry(event: SourceTrackerEvent) {
        val key = ContentKey(event.sourceKey, event.contentId)
        var attempt = 0
        while (true) {
            attempt += 1
            val ok = try {
                withTimeout(syncTimeoutMs) {
                    syncToTracker(event)
                }
            } catch (e: TimeoutCancellationException) {
                lastErrorByContent[key] = SourceTrackerDiagnostics.timeout(event, attempt, syncTimeoutMs)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErrorByContent[key] = SourceTrackerDiagnostics.classify(event, attempt, e)
                false
            }
            if (ok) {
                lastErrorByContent.remove(key)
                return
            }
            if (attempt >= maxRetries) {
                lastErrorByContent.putIfAbsent(key, SourceTrackerDiagnostics.giveUp(event, attempt))
                return
            }
            delay(backoffDelayMillis(attempt))
        }
    }
}

/** Exponential backoff: `2^n * 500ms`, capped at 8s (n = failed attempt number). */
internal fun backoffDelayMillis(attempt: Int): Long {
    val factor = 1L shl attempt.coerceIn(0, 20)
    return minOf(
        SourceTrackerSyncManager.BACKOFF_BASE_MS * factor,
        SourceTrackerSyncManager.BACKOFF_CAP_MS,
    )
}

/**
 * Hilt wiring for the SourceTracker pipeline.
 *
 * The public [SourceTrackerEventEmitter] is bound to the shared [SourceTrackerEventBus]. The
 * [SourceTrackerSyncManager] parameter is the liveness seam for T4B.2: constructing any
 * repository that injects the emitter also constructs (and starts) the manager — favourites
 * and history repositories are created early at app start, so the consumer is always up.
 * [SourceTrackerSyncManager.start] is idempotent.
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceTrackerSyncModule {

    @Provides
    @Singleton
    fun provideSourceTrackerGate(): SourceTrackerGate = DefaultSourceTrackerGate()

    @Provides
    @Singleton
    fun provideSourceTrackerEventEmitter(
        manager: SourceTrackerSyncManager,
    ): SourceTrackerEventEmitter {
        manager.start()
        return SourceTrackerEventBus
    }
}
