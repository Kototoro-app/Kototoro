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
					"AndroidCookieJar save cf_clearance: url=$urlString, value=${maskCookieValue(cookie.value)}, " +
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
					"AndroidCookieJar remove cf_clearance: url=$urlString, value=${maskCookieValue(c.value)}, " +
						"domain=${c.domain}, path=${c.path}, hostOnly=${c.hostOnly}",
				)
			}
			// Rebuild the cookie with a past expiry, preserving all original attributes
			// (domain, path, secure, httpOnly). This is the only reliable way to delete
			// Secure+HttpOnly cookies via Android's CookieManager — empty-value + Max-Age=0
			// fails because the Chromium engine requires an exact attribute match.
			val expired = c.newBuilder().expiresAt(1L).build()
			setCookieBlocking(urlString, expired.toString())
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
		cookieManager.removeAllCookies(continuation::resume)
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
			android.util.Log.w("MihonNetwork", "AndroidCookieJar setCookie timeout: url=$url, valueName=${value.substringBefore("=")}")
		}
	}

	private fun maskCookieValue(value: String?): String {
		if (value.isNullOrEmpty()) return "<empty>"
		return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
	}

	private fun maskRawCookies(raw: String?): String {
		return raw
			.orEmpty()
			.split(";")
			.mapNotNull { rawCookie ->
				val parts = rawCookie.trim().split("=", limit = 2)
				if (parts.size == 2 && parts[0].isNotBlank()) {
					"${parts[0]}=${maskCookieValue(parts[1])}"
				} else {
					null
				}
			}
			.joinToString(",")
			.ifBlank { "<none>" }
	}
}
