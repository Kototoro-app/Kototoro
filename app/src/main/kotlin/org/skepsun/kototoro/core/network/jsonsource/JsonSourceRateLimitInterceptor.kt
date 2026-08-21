package org.skepsun.kototoro.core.network.jsonsource

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate limit interceptor for JSON sources
 * Proactively limits request frequency to avoid hitting server rate limits
 *
 * Features:
 * - Per-domain rate limiting
 * - Configurable minimum delay between requests
 * - Thread-safe implementation
 */
@Singleton
class JsonSourceRateLimitInterceptor @Inject constructor() : Interceptor {

    // Minimum delay between requests to the same domain (in milliseconds)
    private val minDelayMs = 500L

    // Track last request time per domain
    private val lastRequestTime = ConcurrentHashMap<String, AtomicLong>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // Get or create timestamp tracker for this domain
        val timestamp = lastRequestTime.getOrPut(host) { AtomicLong(0) }

        // Calculate required delay
        val now = System.currentTimeMillis()
        val lastRequest = timestamp.get()
        val timeSinceLastRequest = now - lastRequest

        if (timeSinceLastRequest < minDelayMs) {
            val delayNeeded = minDelayMs - timeSinceLastRequest
            // Use runBlocking since Interceptor.intercept is synchronous
            runBlocking {
                delay(delayNeeded)
            }
        }

        // Update last request time
        timestamp.set(System.currentTimeMillis())

        return chain.proceed(request)
    }

    /**
     * Clear rate limit tracking for a specific domain
     * Useful when switching sources or resetting state
     */
    fun clearDomain(domain: String) {
        lastRequestTime.remove(domain)
    }

    /**
     * Clear all rate limit tracking
     */
    fun clearAll() {
        lastRequestTime.clear()
    }
}
