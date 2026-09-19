@file:Suppress("FunctionName")

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit.MINUTES

/**
 * HTTP request helper functions for Mihon compatibility.
 */

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request {
    return GET(url.toHttpUrl(), headers, cache)
}

/**
 * @since extensions-lib 1.4
 */
fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .cacheControl(cache)
        .build()
}

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request {
    return Request.Builder()
        .url(url)
        .post(body)
        .headers(headers)
        .cacheControl(cache)
        .build()
}

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request {
    return Request.Builder()
        .url(url)
        .put(body)
        .headers(headers)
        .cacheControl(cache)
        .build()
}

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request {
    return Request.Builder()
        .url(url)
        .delete(body)
        .headers(headers)
        .cacheControl(cache)
        .build()
}

// ---- OkHttpClient suspend extension functions (Aniyomi extensions-lib 16 compat) ----
// These build the request AND execute it, returning a Response.
//
// Aniyomi's Requests.kt declares the executor twice: once for a String url and once for
// an HttpUrl. Extensions link by JVM descriptor, so both must exist — an extension that
// calls `get(url.toHttpUrl())` resolves RequestsKt.get$default(OkHttpClient, HttpUrl, …)
// and dies with NoSuchMethodError when only the String overload is shipped (issue #538).
//
// Upstream executes through Call.awaitSuccess() while these use Call.await(): callers see
// non-2xx responses as a Response instead of an HttpException. That divergence predates
// this overload and is kept here so the shared Mihon/Aniyomi surface stays non-throwing.

suspend fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(GET(url, headers, cache)).await()
}

suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(GET(url, headers, cache)).await()
}

suspend fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(POST(url, headers, body, cache)).await()
}

suspend fun OkHttpClient.put(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(PUT(url, headers, body, cache)).await()
}

suspend fun OkHttpClient.delete(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(DELETE(url, headers, body, cache)).await()
}
