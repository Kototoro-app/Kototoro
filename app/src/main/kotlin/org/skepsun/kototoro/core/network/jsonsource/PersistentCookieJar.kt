package org.skepsun.kototoro.core.network.jsonsource

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent cookie jar for JSON sources
 * Wraps the app's MutableCookieJar with JSON-source-specific utilities
 *
 * Features:
 * - Automatic cookie persistence via Android's CookieManager
 * - Cross-request cookie sharing
 * - Domain-specific cookie management
 */
@Singleton
class PersistentCookieJar @Inject constructor(
    private val cookieJar: MutableCookieJar
) {
    companion object {
        // Match legado-with-MD3 CookieStore behavior (Cookie header hard limit)
        private const val MAX_COOKIE_HEADER_LENGTH = 4096
    }

    /**
     * Get all cookies for a URL
     * Uses TLD+1 based domain for Legado compatibility
     * @param url Target URL
     * @return List of cookies
     */
    fun getCookies(url: String): List<Cookie> {
        val domain = LegadoNetworkUtils.getSubDomain(url)
        val scheme = try { url.toHttpUrl().scheme } catch (_: Exception) { "https" }
        val normalizedUrl = "$scheme://$domain".toHttpUrl()
        return cookieJar.loadForRequest(normalizedUrl)
    }

    /**
     * Get cookies for a URL as a Cookie header string.
     *
     * Matches legado-with-MD3 CookieStore behavior:
     * - Aggregate by TLD+1 (sub-domain)
     * - Enforce Cookie header length <= 4096 by removing random keys
     */
    fun getCookieHeader(url: String, maxLength: Int = MAX_COOKIE_HEADER_LENGTH): String {
        val cookies = getCookies(url)
        if (cookies.isEmpty()) return ""

        val cookieMap = LinkedHashMap<String, String>(cookies.size)
        for (cookie in cookies) {
            // Last write wins, similar to MD3 mergeCookiesToMap behavior
            cookieMap[cookie.name] = cookie.value
        }

        fun buildHeader(): String {
            if (cookieMap.isEmpty()) return ""
            return cookieMap.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }

        var header = buildHeader()
        while (header.length > maxLength && cookieMap.isNotEmpty()) {
            // Remove a random key (MD3 behavior)
            val removeKey = cookieMap.keys.random()
            removeCookies(url, setOf(removeKey))
            cookieMap.remove(removeKey)
            header = buildHeader()
        }
        return header
    }

    /**
     * Get all cookies for a domain
     * @param domain Domain name (e.g., "example.com")
     * @return List of cookies
     */
    fun getCookiesForDomain(domain: String): List<Cookie> {
        val normalizedDomain = if (domain.startsWith("http")) LegadoNetworkUtils.getSubDomain(domain) else domain
        // Use https by default for cookie retrieval
        val url = "https://$normalizedDomain".toHttpUrl()
        return cookieJar.loadForRequest(url)
    }

    /**
     * Set cookies for a URL
     * Uses TLD+1 based domain for Legado compatibility
     * @param url Target URL
     * @param cookies Cookies to set
     */
    fun setCookies(url: String, cookies: List<Cookie>) {
        val domain = LegadoNetworkUtils.getSubDomain(url)
        val scheme = try { url.toHttpUrl().scheme } catch (_: Exception) { "https" }
        val normalizedUrl = "$scheme://$domain".toHttpUrl()
        cookieJar.saveFromResponse(normalizedUrl, cookies.map { it.withDomain(domain) })
    }

    /**
     * Remove all cookies for a URL
     * @param url Target URL
     */
    fun removeCookies(url: String) {
        val domain = LegadoNetworkUtils.getSubDomain(url)
        val normalizedUrl = "https://$domain".toHttpUrl()
        cookieJar.removeCookies(normalizedUrl, null)
    }

    /**
     * Remove specific cookies for a URL
     * @param url Target URL
     * @param cookieNames Names of cookies to remove
     */
    fun removeCookies(url: String, cookieNames: Set<String>) {
        val domain = LegadoNetworkUtils.getSubDomain(url)
        val normalizedUrl = "https://$domain".toHttpUrl()
        cookieJar.removeCookies(normalizedUrl) { cookie ->
            cookie.name in cookieNames
        }
    }

    /**
     * Clear all cookies
     */
    suspend fun clearAll(): Boolean {
        return cookieJar.clear()
    }

    /**
     * Get a specific cookie by name
     * @param url Target URL
     * @param name Cookie name
     * @return Cookie value or null if not found
     */
    fun getCookie(url: String, name: String): String? {
        return getCookies(url).find { it.name == name }?.value
    }

    /**
     * Check if a cookie exists
     * @param url Target URL
     * @param name Cookie name
     * @return true if cookie exists
     */
    fun hasCookie(url: String, name: String): Boolean {
        return getCookie(url, name) != null
    }

    /**
     * Get the underlying MutableCookieJar for advanced usage
     */
    fun getUnderlyingJar(): MutableCookieJar = cookieJar

    private fun Cookie.withDomain(domain: String): Cookie {
        if (this.domain == domain && !this.hostOnly) return this
        return Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
            .apply {
                if (secure) secure()
                if (httpOnly) httpOnly()
                domain(domain) // widen scope for Legado compatibility
            }
            .build()
    }
}
