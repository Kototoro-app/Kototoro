package org.skepsun.kototoro.core.network

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-host Cloudflare failure cooldown shared by the cover image pipeline and (later) the
 * WebView clearance solver.
 *
 * Unlike the removed per-URL 10-minute negative cache, this is host-scoped and short-lived:
 * a single failed challenge cools the whole host for [cooldownMillis], after which requests are
 * attempted again so a recovering network can succeed. User-initiated refreshes can bypass it
 * by setting [org.skepsun.kototoro.core.util.ext.bypassFailureCooldownKey] on the request.
 */
@Singleton
class CloudflareHostCooldown @Inject constructor() {

    @Volatile
    var cooldownMillis: Long = DEFAULT_COOLDOWN_MS

    /** Overridable clock, exposed for deterministic unit tests. */
    @Volatile
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    fun coolDown(host: String) {
        if (host.isBlank()) return
        val duration = cooldownMillis.coerceAtLeast(0L)
        if (duration == 0L) {
            cooldownUntil.remove(host)
        } else {
            cooldownUntil[host] = nowMillis() + duration
        }
    }

    fun isInCooldown(host: String): Boolean {
        if (host.isBlank()) return false
        val until = cooldownUntil[host] ?: return false
        if (nowMillis() >= until) {
            cooldownUntil.remove(host, until)
            return false
        }
        return true
    }

    fun clear() {
        cooldownUntil.clear()
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
