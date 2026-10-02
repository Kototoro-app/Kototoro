package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

public fun Iterable<Job>.cancelAll(cause: CancellationException? = null) {
	forEach { it.cancel(cause) }
}

public suspend fun <T> Iterable<Deferred<T>>.awaitFirst(): T {
	return channelFlow {
		for (deferred in this@awaitFirst) {
			launch {
				send(deferred.await())
			}
		}
	}.first().also { this@awaitFirst.cancelAll() }
}

public suspend fun <T> Collection<Deferred<T>>.awaitFirst(condition: (T) -> Boolean): T {
	return channelFlow {
		for (deferred in this@awaitFirst) {
			launch {
				val result = deferred.await()
				if (condition(result)) {
					send(result)
				}
			}
		}
	}.first().also { this@awaitFirst.cancelAll() }
}
