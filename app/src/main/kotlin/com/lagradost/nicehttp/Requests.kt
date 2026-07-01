package com.lagradost.nicehttp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Requests(
	var baseClient: OkHttpClient? = null,
	var defaultHeaders: Map<String, String> = emptyMap(),
	var baseUrl: String? = null,
	var defaultCookies: Map<String, String> = emptyMap(),
	var defaultData: Map<String, String> = emptyMap(),
	var defaultCacheTime: Int = 0,
	var defaultCacheUnit: TimeUnit? = null,
	var defaultTimeout: Long = 0L,
	var responseParser: ResponseParser? = null,
) {

	suspend fun get(
		url: String,
		headers: Map<String, String> = emptyMap(),
		referer: String? = null,
		cookies: Map<String, String> = emptyMap(),
		data: Map<String, String> = emptyMap(),
		allowRedirects: Boolean = true,
		cacheTime: Int = defaultCacheTime,
		cacheUnit: TimeUnit? = defaultCacheUnit,
		timeout: Long = defaultTimeout,
		interceptor: Interceptor? = null,
		verify: Boolean = true,
		responseParser: ResponseParser? = this.responseParser,
	): NiceResponse {
		val resolvedUrl = appendQueryParams(resolveUrl(url), defaultData + data)
		val request = requestCreator(
			method = "GET",
			url = resolvedUrl,
			referer = referer,
			headers = mergedHeaders(headers, cookies),
		)
		return execute(
			request = request,
			allowRedirects = allowRedirects,
			cacheTime = cacheTime,
			cacheUnit = cacheUnit,
			timeout = timeout,
			interceptor = interceptor,
			verify = verify,
			responseParser = responseParser,
		)
	}

	suspend fun post(
		url: String,
		headers: Map<String, String> = emptyMap(),
		referer: String? = null,
		cookies: Map<String, String> = emptyMap(),
		data: Map<String, String> = emptyMap(),
		files: Map<String, String> = emptyMap(),
		form: List<Pair<String, String>> = emptyList(),
		json: Any? = null,
		requestBody: RequestBody? = null,
		allowRedirects: Boolean = true,
		cacheTime: Int = defaultCacheTime,
		cacheUnit: TimeUnit? = defaultCacheUnit,
		timeout: Long = defaultTimeout,
		interceptor: Interceptor? = null,
		verify: Boolean = true,
		responseParser: ResponseParser? = this.responseParser,
	): NiceResponse {
		val resolvedUrl = resolveUrl(url)
		val body = requestBody ?: buildPostBody(data = defaultData + data, files = files, form = form, json = json, parser = responseParser)
		val request = requestCreator(
			method = "POST",
			url = resolvedUrl,
			referer = referer,
			headers = mergedHeaders(headers, cookies),
			requestBody = body,
		)
		return execute(
			request = request,
			allowRedirects = allowRedirects,
			cacheTime = cacheTime,
			cacheUnit = cacheUnit,
			timeout = timeout,
			interceptor = interceptor,
			verify = verify,
			responseParser = responseParser,
		)
	}

	private suspend fun execute(
		request: Request,
		allowRedirects: Boolean,
		cacheTime: Int,
		cacheUnit: TimeUnit?,
		timeout: Long,
		interceptor: Interceptor?,
		verify: Boolean,
		responseParser: ResponseParser?,
	): NiceResponse = withContext(Dispatchers.IO) {
		val client = buildClient(
			allowRedirects = allowRedirects,
			cacheTime = cacheTime,
			cacheUnit = cacheUnit,
			timeout = timeout,
			interceptor = interceptor,
			verify = verify,
		)
		client.newCall(request).execute().use { response ->
			val bodyBytes = response.body?.bytes() ?: ByteArray(0)
			val bodyPreview = String(bodyBytes, Charsets.UTF_8).take(500)
			Log.v(TAG, "${request.method} ${response.code} ${request.url} $bodyPreview")
			NiceResponse(
				rawResponse = response.newBuilder().body(bodyBytes.toResponseBody(response.body?.contentType())).build(),
				bodyBytes = bodyBytes,
				contentType = response.body?.contentType()?.toString(),
				responseParser = responseParser,
			)
		}
	}

	private fun buildClient(
		allowRedirects: Boolean,
		cacheTime: Int,
		cacheUnit: TimeUnit?,
		timeout: Long,
		interceptor: Interceptor?,
		verify: Boolean,
	): OkHttpClient {
		val builder = (baseClient ?: OkHttpClient()).newBuilder()
			.followRedirects(allowRedirects)
			.followSslRedirects(allowRedirects)
		if (timeout > 0L) {
			builder.callTimeout(timeout, TimeUnit.MILLISECONDS)
			builder.connectTimeout(timeout, TimeUnit.MILLISECONDS)
			builder.readTimeout(timeout, TimeUnit.MILLISECONDS)
			builder.writeTimeout(timeout, TimeUnit.MILLISECONDS)
		}
		if (cacheTime > 0 && cacheUnit != null) {
			builder.addNetworkInterceptor(Interceptor { chain ->
				chain.proceed(chain.request())
					.newBuilder()
					.header("Cache-Control", "public, max-age=${cacheUnit.toSeconds(cacheTime.toLong())}")
					.build()
			})
		}
		if (interceptor != null) {
			builder.addInterceptor(interceptor)
		}
		if (!verify) {
			builder.ignoreAllSSLErrors()
		}
		return builder.build()
	}

	private fun resolveUrl(url: String): String {
		if (url.startsWith("http://") || url.startsWith("https://")) return url
		val base = baseUrl?.trimEnd('/') ?: return url
		return if (url.startsWith("/")) "$base$url" else "$base/$url"
	}

	private fun mergedHeaders(
		headers: Map<String, String>,
		cookies: Map<String, String>,
	): Map<String, String> {
		val cookieHeader = (defaultCookies + cookies)
			.takeIf { it.isNotEmpty() }
			?.entries
			?.joinToString("; ") { (key, value) -> "$key=$value" }
		return buildMap {
			putAll(defaultHeaders)
			putAll(headers)
			if (!cookieHeader.isNullOrBlank() && "Cookie" !in this && "cookie" !in this) {
				put("Cookie", cookieHeader)
			}
		}
	}

	private fun appendQueryParams(
		url: String,
		params: Map<String, String>,
	): String {
		if (params.isEmpty()) return url
		val builder = url.toHttpUrlOrNull()?.newBuilder() ?: return url
		params.forEach { (key, value) ->
			builder.addQueryParameter(key, value)
		}
		return builder.build().toString()
	}

	private fun buildPostBody(
		data: Map<String, String>,
		files: Map<String, String>,
		form: List<Pair<String, String>>,
		json: Any?,
		parser: ResponseParser?,
	): RequestBody {
		if (files.isNotEmpty()) {
			val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
			data.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
			form.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
			files.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
			return multipart.build()
		}
		if (data.isNotEmpty() || form.isNotEmpty()) {
			val body = FormBody.Builder()
			data.forEach { (key, value) -> body.add(key, value) }
			form.forEach { (key, value) -> body.add(key, value) }
			return body.build()
		}
		if (json != null) {
			val serialized = parser?.writeValueAsString(json) ?: json.toString()
			return serialized.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
		}
		return ByteArray(0).toRequestBody(null, 0, 0)
	}

	companion object {
		private const val TAG = "NiceHTTP"
	}
}

fun requestCreator(
	method: String,
	url: String,
	referer: String? = null,
	headers: Map<String, String> = emptyMap(),
	requestBody: RequestBody? = null,
): Request {
	val builder = Request.Builder().url(url)
	headers.forEach { (key, value) -> builder.header(key, value) }
	if (!referer.isNullOrBlank() && !headers.keys.any { it.equals("Referer", ignoreCase = true) }) {
		builder.header("Referer", referer)
	}
	when (method.uppercase()) {
		"POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null, 0, 0))
		"HEAD" -> builder.head()
		else -> builder.get()
	}
	return builder.build()
}

fun requestCreator(
	method: String,
	url: String,
	headers: Map<String, String> = emptyMap(),
	referer: String? = null,
	cookies: Map<String, String> = emptyMap(),
	data: Map<String, String> = emptyMap(),
	files: Map<String, String> = emptyMap(),
	form: List<Pair<String, String>> = emptyList(),
	json: Any? = null,
	requestBody: RequestBody? = null,
	cacheTime: Int? = null,
	cacheUnit: TimeUnit? = null,
	responseParser: ResponseParser? = null,
): Request {
	val resolvedUrl = if (method.equals("GET", ignoreCase = true) && data.isNotEmpty()) {
		url.toHttpUrlOrNull()?.newBuilder()?.apply {
			data.forEach { (key, value) -> addQueryParameter(key, value) }
		}?.build()?.toString() ?: url
	} else {
		url
	}
	val mergedHeaders = buildMap {
		putAll(headers)
		val cookieHeader = cookies.takeIf { it.isNotEmpty() }
			?.entries
			?.joinToString("; ") { (key, value) -> "$key=$value" }
		if (!cookieHeader.isNullOrBlank() && keys.none { it.equals("Cookie", ignoreCase = true) }) {
			put("Cookie", cookieHeader)
		}
	}
	return requestCreator(
		method = method,
		url = resolvedUrl,
		referer = referer,
		headers = mergedHeaders,
		requestBody = requestBody ?: buildCompatRequestBody(data, files, form, json, responseParser),
	)
}

private fun buildCompatRequestBody(
	data: Map<String, String>,
	files: Map<String, String>,
	form: List<Pair<String, String>>,
	json: Any?,
	parser: ResponseParser?,
): RequestBody? {
	if (data.isEmpty() && files.isEmpty() && form.isEmpty() && json == null) {
		return null
	}
	if (files.isNotEmpty()) {
		val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
		data.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
		form.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
		files.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
		return multipart.build()
	}
	if (data.isNotEmpty() || form.isNotEmpty()) {
		val body = FormBody.Builder()
		data.forEach { (key, value) -> body.add(key, value) }
		form.forEach { (key, value) -> body.add(key, value) }
		return body.build()
	}
	val serialized = parser?.writeValueAsString(json as Any) ?: json.toString()
	return serialized.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
}

fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
	val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
		override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

		override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

		override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
	})
	val trustManager = trustAll[0] as X509TrustManager
	val sslContext = SSLContext.getInstance("SSL")
	sslContext.init(null, trustAll, SecureRandom())
	sslSocketFactory(sslContext.socketFactory, trustManager)
	hostnameVerifier { _, _ -> true }
	return this
}
