@file:JvmName("CookieJarUtils")

package org.koitharu.kotatsu.parsers.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

public fun CookieJar.insertCookies(domain: String, vararg cookies: String) {
	val url = safeUrlOf(domain) ?: return
	saveFromResponse(
		url,
		cookies.mapNotNull {
			Cookie.parse(url, it)
		},
	)
}

public fun CookieJar.insertCookie(domain: String, cookie: Cookie) {
	val url = safeUrlOf(domain) ?: return
	saveFromResponse(url, listOf(cookie))
}

public fun CookieJar.getCookies(domain: String): List<Cookie> {
	val url = safeUrlOf(domain) ?: return emptyList()
	return loadForRequest(url)
}

public fun CookieJar.copyCookies(oldDomain: String, newDomain: String, names: Array<String>? = null) {
	val url = HttpUrl.Builder()
		.scheme(SCHEME_HTTPS)
		.host(oldDomain)
	var cookies = loadForRequest(url.build())
	if (names != null) {
		cookies = cookies.filter { c -> c.name in names }
	}
	url.host(newDomain)
	saveFromResponse(url.build(), cookies)
}

private fun safeUrlOf(domain: String): HttpUrl? = try {
	HttpUrl.Builder()
		.scheme(SCHEME_HTTPS)
		.host(domain)
		.build()
} catch (_: IllegalArgumentException) {
	null
}
