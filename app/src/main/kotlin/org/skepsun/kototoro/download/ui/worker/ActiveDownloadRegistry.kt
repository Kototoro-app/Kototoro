package org.skepsun.kototoro.download.ui.worker

import java.util.UUID

object ActiveDownloadRegistry {
	private val activeWorkers = LinkedHashSet<UUID>()
	private val pausedWorkers = HashSet<UUID>()

	@Synchronized
	fun register(id: UUID, isPaused: Boolean) {
		activeWorkers.add(id)
		if (isPaused) {
			pausedWorkers.add(id)
		} else {
			pausedWorkers.remove(id)
		}
	}

	@Synchronized
	fun unregister(id: UUID) {
		activeWorkers.remove(id)
		pausedWorkers.remove(id)
	}

	@Synchronized
	fun setPaused(id: UUID, paused: Boolean) {
		if (paused) {
			pausedWorkers.add(id)
		} else {
			pausedWorkers.remove(id)
		}
	}

	@Synchronized
	fun isTurn(id: UUID, limit: Int): Boolean {
		if (limit == 11) { // AppSettings.UNLIMITED_SERIES
			return true
		}
		val runningList = activeWorkers.filter { it !in pausedWorkers }
		val index = runningList.indexOf(id)
		return index >= 0 && index < limit
	}
}
