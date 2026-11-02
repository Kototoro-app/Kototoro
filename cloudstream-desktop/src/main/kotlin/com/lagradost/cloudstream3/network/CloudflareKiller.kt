package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cloudstream's Cloudflare interceptor on the Windows host. Plugins create one (`CloudflareKiller()`) and pass it to
 * their requests, or ask it for the cookie headers their images and streams need. Here the shared platform client
 * already clears challenges in its own browser and keeps the clearance in its cookie jar, so the interceptor only
 * lets requests through that client, and the headers come from that jar: the same signatures as the Android
 * resolver, without a second solver racing the platform one.
 */
class CloudflareKiller : Interceptor {

	companion object {
		const val TAG = "CloudflareKiller"
	}

	/** Cookies by host, refreshed from the platform cookie jar each time a host's headers are asked for. */
	val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

	/** The user agent and the platform's cookies for [url], for requests that do not go through the interceptor. */
	fun getCookieHeaders(url: String): Headers {
		val builder = Headers.Builder().set("user-agent", USER_AGENT)
		val cookies = loadCookies(url)
		if (cookies.isNotEmpty()) {
			builder["Cookie"] = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
		}
		return builder.build()
	}

	override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

	private fun loadCookies(url: String): Map<String, String> {
		val httpUrl = url.toHttpUrlOrNull() ?: return emptyMap()
		val cookies = app.baseClient.cookieJar.loadForRequest(httpUrl).associate { it.name to it.value }
		synchronized(savedCookies) {
			if (cookies.isEmpty()) savedCookies.remove(httpUrl.host) else savedCookies[httpUrl.host] = cookies
		}
		return cookies
	}
}
