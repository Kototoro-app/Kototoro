package org.skepsun.kototoro.core.parser.legado

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Port of Legado's ConcurrentRateLimiter.
 * Handles request throttling based on source configuration.
 */
class ConcurrentRateLimiter(private val sourceKey: String, private val concurrentRate: String?) {

    companion object {
        private val recordMap = ConcurrentHashMap<String, Record>()
        private val foregroundWaitingCount = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    }

    private data class Record(
        val isFrequencyBased: Boolean,
        var lastTime: Long,
        var frequency: Int,
        val mutex: Mutex = Mutex()
    )

    suspend fun <T> withLimit(priority: Int = RequestPriority.FOREGROUND, block: suspend () -> T): T {
        if (concurrentRate.isNullOrBlank() || concurrentRate == "0") {
            return block()
        }

        if (priority > RequestPriority.FOREGROUND) {
            // Background request: wait if any foreground request is waiting for the same source
            val counter = foregroundWaitingCount.getOrPut(sourceKey) { java.util.concurrent.atomic.AtomicInteger(0) }
            while (counter.get() > 0) {
                delay(500)
            }
        } else {
            // Foreground request
            val counter = foregroundWaitingCount.getOrPut(sourceKey) { java.util.concurrent.atomic.AtomicInteger(0) }
            counter.incrementAndGet()
        }

        try {
            while (true) {
                val waitTime = checkRate()
                if (waitTime <= 0) break
                delay(waitTime)
            }

            return block()
        } finally {
            if (priority == RequestPriority.FOREGROUND) {
                foregroundWaitingCount[sourceKey]?.decrementAndGet()
            }
            release()
        }
    }

    private suspend fun checkRate(): Long {
        val record = recordMap.getOrPut(sourceKey) {
            val isFrequency = concurrentRate?.contains("/") == true
            Record(isFrequency, System.currentTimeMillis(), 0)
        }

        record.mutex.withLock {
            val rateStr = concurrentRate!!
            if (!record.isFrequencyBased) {
                // Interval based: e.g. "1000" (ms)
                val interval = rateStr.toLongOrNull() ?: return 0L
                if (record.frequency > 0) {
                    return interval // Still busy
                }
                val nextTime = record.lastTime + interval
                val now = System.currentTimeMillis()
                if (now >= nextTime) {
                    record.lastTime = now
                    record.frequency = 1
                    return 0L
                }
                return nextTime - now
            } else {
                // Frequency based: e.g. "3/1000" (3 times per 1000ms)
                val parts = rateStr.split("/")
                val limit = parts[0].toIntOrNull() ?: return 0L
                val period = parts[1].toLongOrNull() ?: return 0L

                val now = System.currentTimeMillis()
                val nextTime = record.lastTime + period

                if (now >= nextTime) {
                    record.lastTime = now
                    record.frequency = 1
                    return 0L
                }

                if (record.frequency >= limit) {
                    return nextTime - now
                } else {
                    record.frequency++
                    return 0L
                }
            }
        }
    }

    private suspend fun release() {
        if (concurrentRate.isNullOrBlank() || concurrentRate == "0") return
        val record = recordMap[sourceKey] ?: return
        if (!record.isFrequencyBased) {
            record.mutex.withLock {
                record.frequency = 0
            }
        }
    }
}
