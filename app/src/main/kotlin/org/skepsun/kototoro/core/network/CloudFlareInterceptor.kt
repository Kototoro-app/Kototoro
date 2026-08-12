package org.skepsun.kototoro.core.network

import android.util.Log
import dagger.Lazy
import okhttp3.Interceptor
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.IOException
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

class CloudFlareInterceptor(
	private val webViewExecutor: Lazy<WebViewExecutor>? = null,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val source = request.tag(ContentSource::class.java) 
			?: request.headers[org.skepsun.kototoro.core.network.CommonHeaders.MANGA_SOURCE]?.let { org.skepsun.kototoro.core.model.ContentSource(it) }
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> {
				val policy = request.tag(CloudFlareHandlingPolicy::class.java)
				if (policy?.allowBlockedResponse == true) {
					Log.w(
						TAG,
						"CloudFlare blocked response allowed by request policy: url=${request.url} source=${source?.name}",
					)
					response
				} else {
					response.closeThrowing(
						CloudFlareBlockedException(
							url = request.url.toString(),
							source = source,
						),
					)
				}
			}

			CloudFlareHelper.PROTECTION_CAPTCHA -> {
				val error = CloudFlareProtectedException(
					url = CloudFlareHelper.getBrowserChallengeUrl(request.url.toString()),
					source = source,
					headers = request.headers,
				)
				val policy = request.tag(CloudFlareHandlingPolicy::class.java)
				if (policy == null && webViewExecutor != null) {
					val browserResponse = response.use { executeWithBrowserTransport(request) }
					if (browserResponse != null) return browserResponse
				}
				if (policy?.allowCaptchaResponse == true) {
					policy.onCaptchaDetected?.invoke(error)
					Log.w(
						TAG,
						"CloudFlare captcha response allowed by request policy: url=${request.url} source=${source?.name}",
					)
					response
				} else {
					response.closeThrowing(error)
				}
			}

			else -> response
		}
	}

	private fun executeWithBrowserTransport(request: Request): Response? {
		val executor = webViewExecutor ?: return null
		if (request.tag(ContentSource::class.java) == null) {
			Log.w(TAG, "Browser transport skipped: missing ContentSource tag, url=${request.url}")
			return null
		}
		if (request.method != "GET" && request.method != "POST") return null
		if (!request.isTextTransportRequest()) return null
		val body = request.body?.let { requestBody ->
			if (requestBody.isDuplex() || requestBody.isOneShot()) return null
			if (requestBody.contentLength() > MAX_BROWSER_REQUEST_BODY_BYTES) return null
			Buffer().use { buffer ->
				requestBody.writeTo(buffer)
				if (buffer.size > MAX_BROWSER_REQUEST_BODY_BYTES) return null
				buffer.readUtf8()
			}
		}
		val browserResult = runCatching {
			runBlocking {
				executor.get().fetchWithBrowserContext(
					url = request.url.toString(),
					method = request.method,
					body = body,
					userAgent = request.header("User-Agent"),
					headers = request.browserTransportHeaders(),
				)
			}
		}.onFailure { error ->
			Log.w(TAG, "Browser transport fallback failed: ${request.url}", error)
		}.getOrNull() ?: return null
		if (browserResult.status !in 100..599) {
			Log.w(TAG, "Browser transport rejected invalid HTTP status: status=${browserResult.status}, url=${request.url}")
			return null
		}
		if (browserResult.status == 403 || browserResult.status == 503) {
			Log.w(TAG, "Browser transport exhausted same-session challenge: ${request.url}")
		}
		return browserResult.toResponse(request)
	}

	private fun Request.isTextTransportRequest(): Boolean {
		val accept = header("Accept")?.lowercase() ?: return true
		return BINARY_MEDIA_TYPES.none(accept::contains)
	}

	private fun WebViewExecutor.BrowserFetchResult.toResponse(request: Request): Response {
		val responseHeaders = Headers.Builder()
		headers.forEach { (name, value) ->
			if (!name.equals("content-length", ignoreCase = true) && value.isNotBlank()) {
				runCatching { responseHeaders.add(name, value) }
			}
		}
		responseHeaders.add("X-Kototoro-WebView-Final-Url", url)
		val mediaType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
			?.value?.toMediaTypeOrNull()
		return Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(status)
			.message(statusText.ifBlank { "Browser Transport" })
			.headers(responseHeaders.build())
			.body(body.toResponseBody(mediaType))
			.build()
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}

	private companion object {
		const val TAG = "CloudFlareInterceptor"
		const val MAX_BROWSER_REQUEST_BODY_BYTES = 2L * 1024L * 1024L
		val BINARY_MEDIA_TYPES = setOf("image/", "audio/", "video/", "application/octet-stream")
	}
}
