package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Admits queued chapters up to the configured limit, giving prioritized chapters the next free slot. */
internal class DesktopDownloadSlots(private val limit: () -> Int?) {
    private val waiting = linkedMapOf<String, Boolean>()
    private val running = mutableSetOf<String>()
    private val revisions = MutableStateFlow(0L)

    @Synchronized
    fun enqueue(key: String) {
        waiting[key] = false
        refresh()
    }

    @Synchronized
    fun togglePriority(key: String): Boolean? {
        val prioritized = waiting[key]?.not() ?: return null
        waiting[key] = prioritized
        refresh()
        return prioritized
    }

    @Synchronized
    fun remove(key: String) {
        if (waiting.remove(key) != null || running.remove(key)) refresh()
    }

    @Synchronized
    fun refresh() {
        revisions.value++
    }

    suspend fun <T> withSlot(key: String, block: suspend () -> T): T {
        try {
            while (true) {
                val revision = revisions.value
                if (tryAdmit(key)) return block()
                revisions.first { it != revision }
            }
        } finally {
            remove(key)
        }
    }

    @Synchronized
    private fun tryAdmit(key: String): Boolean {
        if (key !in waiting) return false
        val maximum = limit()
        if (maximum != null) {
            val freeSlots = maximum - running.size
            if (freeSlots <= 0) return false
            val next = waiting.entries.sortedByDescending { it.value }.take(freeSlots)
            if (next.none { it.key == key }) return false
        }
        waiting.remove(key)
        running.add(key)
        refresh()
        return true
    }
}

/**
 * Android's `DownloadSlowdownDispatcher` for one series: a source that asked for spacing gets its requests spaced, so
 * two page fetches of the same title cannot hit the site at the same instant. The window is per key (the content id)
 * and moves forward on every request, which is exactly the Android arithmetic; a zero delay turns it off.
 */
internal class DesktopRequestPacer(
    private val delayMs: () -> Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val nextAllowed = mutableMapOf<Any, Long>()

    suspend fun await(key: Any) {
        val spacing = delayMs()
        if (spacing <= 0L) return
        val wait = mutex.withLock {
            val now = clock()
            val scheduled = maxOf(now, nextAllowed[key] ?: 0L)
            nextAllowed[key] = scheduled + spacing
            scheduled - now
        }
        if (wait > 0L) delay(wait)
    }
}
