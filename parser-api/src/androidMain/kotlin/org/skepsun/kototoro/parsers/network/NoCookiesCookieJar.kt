package org.skepsun.kototoro.parsers.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A CookieJar implementation that never loads or saves cookies.
 * Useful for performing requests that must not carry session state,
 * such as credential login where existing cookies can interfere.
 */
public class NoCookiesCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // no-op
    }
}