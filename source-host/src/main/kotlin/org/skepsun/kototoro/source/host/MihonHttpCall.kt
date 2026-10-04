package org.skepsun.kototoro.source.host

import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Public OkHttp callback ABI supplied by the embedding platform; no HTTP dependency or SDK copy is shipped. */
internal object MihonHttpCall {
    suspend fun await(lease: MihonJarRegistry.SourceLease, request: Any,
        onCompleted: () -> Unit): MihonImageResponse = suspendCancellableCoroutine { continuation ->
        val hold = lease.retain()
        val completed = AtomicBoolean()
        fun finish() {
            try { onCompleted() } finally { hold.close() }
        }
        try {
            val client = requireNotNull(MihonReflection.call(lease.instance, "getClient"))
            val call = requireNotNull(MihonReflection.call(client, "newCall", request))
            continuation.invokeOnCancellation { cause ->
                try { MihonReflection.call(call, "cancel") }
                catch (cleanup: Throwable) { cause?.addSuppressed(cleanup) }
            }
            if (!continuation.isActive) {
                if (completed.compareAndSet(false, true)) finish()
                return@suspendCancellableCoroutine
            }
            val callbackType = Class.forName("okhttp3.Callback", true, lease.loader)
            val callback = Proxy.newProxyInstance(lease.loader, arrayOf(callbackType)) { proxy, method, arguments ->
                when (method.name) {
                    "toString" -> "Kototoro cover callback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "onResponse", "onFailure" -> {
                        val thread = Thread.currentThread()
                        val previous = thread.contextClassLoader
                        thread.contextClassLoader = lease.loader
                        try {
                            if (completed.compareAndSet(false, true)) {
                                try {
                                    if (method.name == "onFailure") {
                                        continuation.resumeWith(Result.failure(arguments!![1] as Throwable))
                                    } else {
                                        val response = MihonImageResponse(requireNotNull(arguments?.get(1)), lease)
                                        continuation.resume(response, onCancellation = { cause, owned, _ ->
                                            try { owned.close() }
                                            catch (cleanup: Throwable) { cause.addSuppressed(cleanup) }
                                        })
                                    }
                                } catch (error: Throwable) { continuation.resumeWith(Result.failure(error)) }
                                finally { finish() }
                            }
                        } finally { thread.contextClassLoader = previous }
                        null
                    }
                    else -> null
                }
            }
            MihonReflection.call(call, "enqueue", callback)
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                try { finish() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                continuation.resumeWith(Result.failure(error))
            }
        }
    }
}
