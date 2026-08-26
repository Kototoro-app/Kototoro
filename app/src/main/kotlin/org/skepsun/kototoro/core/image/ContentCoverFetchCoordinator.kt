package org.skepsun.kototoro.core.image

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentCoverFetchCoordinator @Inject constructor() {

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T {
        val entry = entries.compute(key) { _, current ->
            (current ?: Entry()).apply { users++ }
        }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            entries.computeIfPresent(key) { _, current ->
                current.apply { users-- }.takeIf { it.users > 0 }
            }
        }
    }

    private class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}
