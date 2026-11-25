package org.skepsun.kototoro.download.ui.worker

import java.util.UUID

object ActiveDownloadRegistry {
    private val activeWorkers = LinkedHashSet<UUID>()
    private val pausedWorkers = HashSet<UUID>()
    private val prioritizedWorkers = HashSet<UUID>()
    private val admittedWorkers = HashSet<UUID>()

    @Synchronized
    fun register(id: UUID, isPaused: Boolean, prioritized: Boolean = false) {
        activeWorkers.add(id)
        setPrioritized(id, prioritized)
        if (isPaused) {
            pausedWorkers.add(id)
            admittedWorkers.remove(id)
        } else {
            pausedWorkers.remove(id)
        }
    }

    @Synchronized
    fun unregister(id: UUID) {
        activeWorkers.remove(id)
        prioritizedWorkers.remove(id)
        admittedWorkers.remove(id)
        pausedWorkers.remove(id)
    }

    @Synchronized
    fun setPaused(id: UUID, paused: Boolean) {
        if (paused) {
            pausedWorkers.add(id)
            admittedWorkers.remove(id)
        } else {
            pausedWorkers.remove(id)
        }
    }

    @Synchronized
    fun setPrioritized(id: UUID, prioritized: Boolean) {
        if (prioritized && id in activeWorkers) prioritizedWorkers.add(id) else prioritizedWorkers.remove(id)
    }

    @Synchronized
    fun isTurn(id: UUID, limit: Int): Boolean {
        if (limit >= 11) { // AppSettings.UNLIMITED_SERIES
            return true
        }
        if (id in admittedWorkers) return true
        val freeSlots = limit - admittedWorkers.size
        if (freeSlots <= 0) return false
        val waiting = activeWorkers.asSequence()
            .filter { it !in pausedWorkers && it !in admittedWorkers }
            .sortedByDescending { it in prioritizedWorkers }
            .take(freeSlots)
        if (id !in waiting) return false
        admittedWorkers.add(id)
        return true
    }
}
