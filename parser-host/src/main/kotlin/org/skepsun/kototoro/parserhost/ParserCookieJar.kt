package org.skepsun.kototoro.parserhost

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Android's WebView cookie store keeps session cookies across restarts, and parsers rely on it to stay logged in: they
 * save a login token as a cookie without an expiry. A store that writes only persistent cookies to disk (the Mobile
 * API's) would forget the login on the next launch, so cookies a parser or its login page saves itself are kept as
 * long-lived ones. Cookies a site sets on its responses go to the client's store unchanged.
 */
internal class ParserCookieJar(private val delegate: CookieJar) : CookieJar by delegate {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
        delegate.saveFromResponse(url, cookies.map(::keptAcrossRestarts))

    companion object {
        /** About thirteen months, the longest lifetime browsers grant a cookie. */
        private const val SESSION_LIFETIME_MILLIS = 400L * 24 * 60 * 60 * 1000

        fun keptAcrossRestarts(cookie: Cookie, now: Long = System.currentTimeMillis()): Cookie =
            if (cookie.persistent) cookie else cookie.newBuilder().expiresAt(now + SESSION_LIFETIME_MILLIS).build()
    }
}
