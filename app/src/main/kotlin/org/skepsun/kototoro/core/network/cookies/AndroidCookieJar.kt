package org.skepsun.kototoro.core.network.cookies

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AndroidCookieJar : MutableCookieJar {

    private val cookieManager = CookieManager.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    @WorkerThread
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val rawCookie = cookieManager.getCookie(url.toString()) ?: return emptyList()
        val cookies = rawCookie.split(';').mapNotNull {
            Cookie.parse(url, it)
        }
        val deduplicated = cookies.distinctBy { it.name to it.value }
        if (deduplicated.size != cookies.size) {
            android.util.Log.d(
                "MihonNetwork",
                "AndroidCookieJar deduplicated identical cookies: url=${url.host}, " +
                    "before=${cookies.size}, after=${deduplicated.size}",
            )
        }
        cookies.firstOrNull { it.name == "cf_clearance" }?.let { clearance ->
            logClearanceLoad(url.host, clearance.value)
        }
        return deduplicated
    }

    @WorkerThread
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            return
        }
        val urlString = url.toString()
        for (cookie in cookies) {
            if (cookie.name == "cf_clearance") {
                android.util.Log.i(
                    "MihonNetwork",
                    "AndroidCookieJar save cf_clearance: url=$urlString, " +
                        "fingerprint=${sensitiveValueFingerprint(cookie.value)}, " +
                        "domain=${cookie.domain}, path=${cookie.path}, expiresAt=${cookie.expiresAt}, hostOnly=${cookie.hostOnly}, secure=${cookie.secure}, httpOnly=${cookie.httpOnly}",
                )
            }
            cookieManager.setCookie(urlString, cookie.toString())
        }
    }

    override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
        val cookies = loadForRequest(url)
        if (cookies.isEmpty()) {
            return
        }
        val urlString = url.toString()
        for (c in cookies) {
            if (predicate != null && !predicate.test(c)) {
                continue
            }
            if (c.name == "cf_clearance") {
                android.util.Log.i(
                    "MihonNetwork",
                    "AndroidCookieJar remove cf_clearance: url=$urlString, " +
                        "fingerprint=${sensitiveValueFingerprint(c.value)}, " +
                        "domain=${c.domain}, path=${c.path}, hostOnly=${c.hostOnly}",
                )
            }
            // Match Komikku/Mihon's direct deletion strategy first. CookieManager resolves
            // the existing host/path identity from the original request URL.
            setCookieBlocking(urlString, "${c.name}=;Max-Age=0")
            // CookieManager only exposes name=value when reading cookies, so Cookie.parse()
            // cannot recover whether the stored cookie was host-only or Domain scoped.
            // Expire every possible domain identity instead of trusting reconstructed attrs.
            buildCookieDeletionHeaders(c.name, url.host, setOf(c.path, "/")).forEach { tombstone ->
                setCookieBlocking(urlString, tombstone)
            }
        }
        cookieManager.flush()
        if (cookies.any { it.name == "cf_clearance" && (predicate == null || predicate.test(it)) }) {
            android.util.Log.i(
                "MihonNetwork",
                "AndroidCookieJar after remove cf_clearance: url=$urlString, raw=[${maskRawCookies(cookieManager.getCookie(urlString))}]",
            )
        }
    }

    override suspend fun clear() = suspendCoroutine<Boolean> { continuation ->
        val clearCookies = {
            cookieManager.removeAllCookies(continuation::resume)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearCookies()
        } else if (!mainHandler.post(clearCookies)) {
            continuation.resume(false)
        }
    }

    private fun setCookieBlocking(url: String, value: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cookieManager.setCookie(url, value)
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            cookieManager.setCookie(url, value) {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            android.util.Log.w("MihonNetwork", "AndroidCookieJar setCookie timed out")
        }
    }

    private fun maskRawCookies(raw: String?): String {
        return raw
            .orEmpty()
            .split(";")
            .mapNotNull { rawCookie ->
                val parts = rawCookie.trim().split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    "${parts[0]}=${sensitiveValueFingerprint(parts[1])}"
                } else {
                    null
                }
            }
            .joinToString(",")
            .ifBlank { "<none>" }
    }

    private fun logClearanceLoad(host: String, value: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val fingerprint = sensitiveValueFingerprint(value)
        val previous = clearanceLoadLogStates[host]
        if (previous?.fingerprint == fingerprint && now - previous.loggedAtMs < CLEARANCE_LOG_INTERVAL_MS) {
            return
        }
        clearanceLoadLogStates[host] = ClearanceLogState(fingerprint, now)
        android.util.Log.d(
            "MihonNetwork",
            "AndroidCookieJar load cf_clearance: host=$host, fingerprint=$fingerprint",
        )
    }

    private data class ClearanceLogState(
        val fingerprint: String,
        val loggedAtMs: Long,
    )

    private companion object {
        const val CLEARANCE_LOG_INTERVAL_MS = 2_000L
        val clearanceLoadLogStates = ConcurrentHashMap<String, ClearanceLogState>()
    }
}

internal fun buildCookieDeletionHeaders(
    name: String,
    host: String,
    paths: Set<String>,
): List<String> {
    val normalizedPaths = paths.mapTo(LinkedHashSet()) { path ->
        path.takeIf { it.startsWith('/') } ?: "/"
    }
    val domains = linkedSetOf(host, ".$host")
    return buildList {
        // Match CookieManager's default-path deletion used by Mihon/Komikku before
        // covering attributes that cannot be recovered from getCookie().
        add("$name=;Max-Age=0")
        for (path in normalizedPaths) {
            val base = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
            add("$base; Secure")
            for (domain in domains) {
                add("$base; Domain=$domain; Secure")
            }
        }
    }.distinct()
}
