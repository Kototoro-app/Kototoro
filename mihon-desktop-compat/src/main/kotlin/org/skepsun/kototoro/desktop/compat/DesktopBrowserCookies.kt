package org.skepsun.kototoro.desktop.compat

import eu.kanade.tachiyomi.network.PersistentCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.net.HttpCookie

/** Uses the SDK's existing cookie store. A browser owner calls push/pull serially around its operations. */
class DesktopBrowserCookies internal constructor(
    private val store: PersistentCookieStore,
    private val browser: DesktopWebViewBridge,
) {
    private data class Key(val name: String, val domain: String, val path: String)
    private var mirrored = emptyMap<Key, Cookie>()
    private var synchronizedWithBrowser = false

    suspend fun push() {
        val current = store.getStoredCookies().associateBy(::key)
        // A reused native profile must not resurrect a token removed while no browser was open.
        val previousCookies = if (synchronizedWithBrowser) mirrored else browser.getCookies("").map(::cookie).associateBy(::key)
        for ((identity, previous) in previousCookies) if (identity !in current) {
            browser.deleteCookie(previous.name, browserDomain(previous), previous.path)
        }
        for (cookie in current.values) {
            // Leave browser changes alone when the SDK has not changed this cookie since our last synchronization.
            if (!synchronizedWithBrowser || mirrored[key(cookie)] != cookie) {
                browser.setCookie(url(cookie).toString(), cookie.name, cookie.value, browserDomain(cookie), cookie.path,
                    isHttpOnly = cookie.httpOnly, isSecure = cookie.secure,
                    expiresEpochMillis = cookie.expiresAt.takeIf { cookie.persistent })
            }
        }
        mirrored = current
        synchronizedWithBrowser = true
    }

    suspend fun pull() {
        // Empty URI is WebView2's documented all-cookie query, including cookies at sibling paths.
        val current = browser.getCookies("").map(::cookie).associateBy(::key)
        val sdkNow = store.getStoredCookies().associateBy(::key)
        for ((identity, previous) in mirrored) if (identity !in current && sdkNow[identity] == previous) {
            val expired = HttpCookie(previous.name, previous.value).apply {
                domain = previous.domain; path = previous.path
            }
            store.remove(url(previous).toUri(), expired)
        }
        val updates = current.values.filter { incoming ->
            val identity = key(incoming)
            // Keep SDK changes made while a page was open unless the browser also changed this cookie.
            sdkNow[identity] == mirrored[identity] || incoming != mirrored[identity]
        }
        updates.groupBy(::url).forEach { (origin, values) -> store.addAll(origin, values) }
        mirrored = current
    }

    private fun cookie(value: DesktopWebCookie): Cookie {
        val domain = requireNotNull(value.domain) { "Browser cookie has no domain" }
        val builder = Cookie.Builder().name(value.name).value(value.value).path(value.path ?: "/")
        if (domain.startsWith('.')) builder.domain(domain.removePrefix(".")) else builder.hostOnlyDomain(domain)
        if (value.isHttpOnly) builder.httpOnly()
        if (value.isSecure) builder.secure()
        if (!value.isSession) builder.expiresAt(requireNotNull(value.expiresEpochMillis))
        return builder.build()
    }

    private fun key(value: Cookie) = Key(value.name, value.domain, value.path)
    private fun browserDomain(value: Cookie) = if (value.hostOnly) value.domain else ".${value.domain}"
    private fun url(value: Cookie): HttpUrl = HttpUrl.Builder().scheme(if (value.secure) "https" else "http")
        .host(value.domain).encodedPath(value.path).build()
}
