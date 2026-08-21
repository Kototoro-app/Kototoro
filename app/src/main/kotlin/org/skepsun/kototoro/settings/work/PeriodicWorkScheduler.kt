package org.skepsun.kototoro.settings.work

interface PeriodicWorkScheduler {

    suspend fun schedule()

    suspend fun unschedule()

    suspend fun isScheduled(): Boolean
}
