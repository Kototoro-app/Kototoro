package org.skepsun.kototoro.core.util.ext

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitCancellable(): T {
    if (isComplete) {
        val e = exception
        return if (e == null) {
            if (isCanceled) {
                throw CancellationException("Task $this was cancelled normally.")
            } else {
                @Suppress("UNCHECKED_CAST")
                result as T
            }
        } else {
            throw e
        }
    }

    return suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val e = task.exception
            if (e == null) {
                if (task.isCanceled) {
                    continuation.cancel()
                } else {
                    if (continuation.isActive) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(task.result as T)
                    }
                }
            } else {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }
}
