package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/** Cloudstream-compatible resolver that does not erase cookies owned by other plugins. */
class CloudflareKiller : Interceptor {

	companion object {
		const val TAG = "CloudflareKiller"
		private val ERROR_CODES = listOf(403, 503)
		private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

		fun parseCookieMap(cookie: String): Map<String, String> = cookie
			.split(';')
			.mapNotNull { entry ->
				val parts = entry.split('=', limit = 2)
				val name = parts.getOrNull(0)?.trim().orEmpty()
				val value = parts.getOrNull(1)?.trim().orEmpty()
				if (name.isBlank() || value.isBlank()) null else name to value
			}
			.toMap()
	}

	val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

	fun getCookieHeaders(url: String): Headers {
		val userAgentHeaders = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) }.orEmpty()
		return buildHeaders(userAgentHeaders, savedCookies[URI(url).host].orEmpty())
	}

	override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
		val request = chain.request()
		val cookies = savedCookies[request.url.host]
		if (cookies != null) {
			return@runBlocking proceed(request, cookies)
		}

		val response = chain.proceed(request)
		if (response.header("Server") !in CLOUDFLARE_SERVERS || response.code !in ERROR_CODES) {
			return@runBlocking response
		}
		response.close()
		bypassCloudflare(request)?.let { resolved ->
			Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
			return@runBlocking resolved
		}
		debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
		chain.proceed(request)
	}

	private fun getWebViewCookie(url: String): String? = safe {
		CookieManager.getInstance().getCookie(url)
	}

	private fun trySolveWithSavedCookies(request: Request): Boolean {
		return getWebViewCookie(request.url.toString())?.let { cookie ->
			cookie.contains("cf_clearance").also { solved ->
				if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
			}
		} ?: false
	}

	private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
		val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let { mapOf("user-agent" to it) }.orEmpty()
		val headers = buildHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
		return app.baseClient.newCall(request.newBuilder().headers(headers).build()).await()
	}

	private suspend fun bypassCloudflare(request: Request): Response? {
		if (!trySolveWithSavedCookies(request)) {
			Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
			WebViewResolver(
				interceptUrl = Regex(".^"),
				userAgent = null,
				useOkhttp = false,
				additionalUrls = listOf(Regex(".")),
			).resolveUsingWebView(request.url.toString()) {
				trySolveWithSavedCookies(request)
			}
		}
		val cookies = savedCookies[request.url.host] ?: return null
		return proceed(request, cookies)
	}

	private fun buildHeaders(headers: Map<String, String>, cookies: Map<String, String>): Headers {
		val builder = Headers.Builder()
		if (headers.keys.none { it.equals("user-agent", ignoreCase = true) }) {
			builder["user-agent"] = USER_AGENT
		}
		headers.forEach { (name, value) -> builder[name] = value }
		if (cookies.isNotEmpty()) {
			builder["Cookie"] = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
		}
		return builder.build()
	}
}
