package org.skepsun.kototoro.core.network.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.network.CloudflareHostCooldown
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Host-level coordination for the off-screen Cloudflare WebView solver (CloudflareStrategy.MIHON).
 *
 * Guarantees:
 * - At most one in-flight solve per host: the caller that acquires the host gate drives the
 *   solve, every concurrent caller for the same host attaches to that SAME solve, so on success
 *   all of them reuse the freshly written `cf_clearance` cookie when they retry their own request.
 * - A failed solve cools the whole host via [CloudflareHostCooldown]; during the cooldown window
 *   no new WebView is created for that host.
 * - Waiters are cancellable: a cancelled waiter exits immediately. When the LAST waiter for a
 *   host disappears while a solve is still in flight, the solve itself is cancelled (which makes
 *   [WebViewClearanceSolver] stop loading and destroy its WebView).
 *
 * The solve is supplied as a [suspend] [action] so tests use a fake solver and no real WebView
 * is ever touched in JVM unit tests. Callers on blocking threads (OkHttp interceptors) bridge
 * with [kotlinx.coroutines.runBlocking].
 */
@Singleton
class CloudflareSolveCoordinator @Inject constructor(
    private val hostCooldown: CloudflareHostCooldown,
) {

    // The solve runs on an independent scope so a single waiter's cancellation or death never
    // kills a solve that other waiters still depend on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gates = ConcurrentHashMap<String, HostGate>()

    /**
     * Runs [action] (typically `solver.solve(request)`) at most once concurrently per [host].
     *
     * Returns the shared solve result, or `false` immediately when the host is in cooldown.
     */
    suspend fun solve(host: String, action: suspend () -> Boolean): Boolean {
        if (hostCooldown.isInCooldown(host)) {
            return false
        }
        val gate = gates.computeIfAbsent(host) { HostGate(host) } ?: return false
        return gate.join(action)
    }

    private inner class HostGate(private val host: String) {

        private val mutex = Mutex()

        /** The solve currently in flight for this host, if any. Guarded by [mutex]. */
        private var active: Deferred<Boolean>? = null

        /** Number of callers attached to the in-flight (or just-finished) solve. Guarded by [mutex]. */
        private var waiters = 0

        suspend fun join(action: suspend () -> Boolean): Boolean {
            val deferred = mutex.withLock {
                waiters++
                active?.takeIf { !it.isCompleted }
                    ?: scope.async {
                        runSolve(action)
                    }.also { active = it }
            }
            return try {
                deferred.await()
            } finally {
                mutex.withLock {
                    waiters--
                    if (waiters <= 0) {
                        waiters = 0
                        if (active === deferred) {
                            active = null
                            if (deferred.isActive) {
                                // No one needs the result anymore: stop the solve (destroys the WebView).
                                deferred.cancel()
                            }
                        }
                    }
                }
            }
        }

        private suspend fun runSolve(action: suspend () -> Boolean): Boolean {
            val result = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!result) {
                hostCooldown.coolDown(host)
            }
            return result
        }
    }
}
