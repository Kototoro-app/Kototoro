package org.skepsun.kototoro.core.exceptions.resolve

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CloudFlareSingleFlight {

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Boolean>>()

    suspend fun run(host: String, resolve: suspend () -> Boolean): Boolean {
        val (result, isLeader) = mutex.withLock {
            val existing = inFlight[host]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<Boolean>().also { inFlight[host] = it } to true
            }
        }
        if (!isLeader) return result.await()

        try {
            return resolve().also(result::complete)
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock {
                inFlight.remove(host, result)
            }
        }
    }
}
