package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Admits chapters into the queue up to the series cap the download settings ask for.
 *
 * Android's `DownloadWorker` runs `downloadMaxActiveSeries` series at once and serialises inside a series; the Windows
 * queue used to run every chapter under one mutex. The limit is read on each admission, so raising it on the settings
 * page lets the next queued chapter start without a restart, and `null` (the "no limit" choice) admits immediately.
 * A waiter that is cancelled simply stops waiting: it never holds a permit it did not use.
 */
internal class DesktopDownloadSlots(private val limit: () -> Int?) {
    private val mutex = Mutex()
    private val running = MutableStateFlow(0)

    suspend fun <T> withSlot(block: suspend () -> T): T {
        admit()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun admit() {
        while (true) {
            val admitted = mutex.withLock {
                val maximum = limit()
                if (maximum == null || running.value < maximum) {
                    running.value += 1
                    true
                } else {
                    false
                }
            }
            if (admitted) return
            // Every release publishes a new count, and the predicate is re-read with the current limit.
            running.first { count ->
                val maximum = limit()
                maximum == null || count < maximum
            }
        }
    }

    private suspend fun release() {
        mutex.withLock { running.value -= 1 }
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
