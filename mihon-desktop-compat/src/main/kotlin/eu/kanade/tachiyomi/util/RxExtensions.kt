package eu.kanade.tachiyomi.util

import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits a single value from the [Observable] and returns it.
 */
suspend fun <T> Observable<T>.awaitSingle(): T = suspendCancellableCoroutine { continuation ->
    val subscription: Subscription = first().subscribe(
        { continuation.resume(it) },
        { continuation.resumeWithException(it) }
    )
    
    continuation.invokeOnCancellation {
        subscription.unsubscribe()
    }
}
