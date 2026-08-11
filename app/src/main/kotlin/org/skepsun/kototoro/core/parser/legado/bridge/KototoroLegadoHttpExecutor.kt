package org.skepsun.kototoro.core.parser.legado.bridge

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.legado.ConcurrentRateLimiter
import org.skepsun.kototoro.core.parser.legado.LegadoCloudFlareResolver
import org.skepsun.kototoro.core.parser.legado.RequestPriority
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import org.skepsun.kototoro.core.util.EncodingDetect
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Kototoro 对 LegadoRequestPlan 的默认执行器实现。
 *
 * 当前实现尽量保持与 LegadoRepository 既有请求行为一致，只把执行逻辑移出仓库类。
 */
class KototoroLegadoHttpExecutor(
    private val source: ContentSource,
    private val config: LegadoBookSource,
    private val httpClient: LegadoHttpClient,
    private val rateLimiter: ConcurrentRateLimiter,
    private val configHeadersProvider: () -> Map<String, String>,
    private val loginHeadersProvider: () -> Map<String, String>,
    private val sourceUserAgentProvider: () -> String,
) : LegadoHttpExecutor {

    companion object {
        private const val TAG = "KototoroLegadoHttpExecutor"
        private val DATA_URI_REGEX = Regex("^data:.*?;base64,(.*)", RegexOption.IGNORE_CASE)

        fun parseHeaderJson(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val normalized = if (raw.contains('\'')) raw.replace("'", "\"") else raw
                val json = JSONObject(normalized)
                buildMap {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.opt(key)
                        if (value != null && value != JSONObject.NULL) {
                            val content = value.toString().removeSurrounding("\"")
                            if (content.isNotBlank()) {
                                put(key, content)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }

    override suspend fun execute(plan: LegadoRequestPlan): LegadoHttpResponse {
        val maxRetries = plan.retry.coerceAtLeast(0) + 1
        if (plan.url.startsWith("data:", ignoreCase = true)) {
            return executeDataUri(plan)
        }
        if (!plan.url.startsWith("http", ignoreCase = true)) {
            Log.w(TAG, "Skipping non-HTTP request: ${plan.url}")
            throw IllegalArgumentException("Unsupported non-HTTP URL: ${plan.url}")
        }

        repeat(maxRetries) { attempt ->
            try {
                val priority = kotlin.coroutines.coroutineContext[RequestPriority]?.priority
                    ?: RequestPriority.FOREGROUND

                return rateLimiter.withLimit(priority) {
                    val cookieSyncUrl = config.bookSourceUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: plan.url
                    LegadoCloudFlareResolver.syncCloudFlareCookies(cookieSyncUrl)

                    val headersWithUa = mutableMapOf<String, String>()
                    mergeHeaders(headersWithUa, configHeadersProvider())
                    mergeHeaders(headersWithUa, loginHeadersProvider())
                    mergeHeaders(headersWithUa, plan.headers)
                    applyCookieHeaders(headersWithUa, plan)

                    if (!headersWithUa.containsKeyIgnoreCase("User-Agent")) {
                        headersWithUa["User-Agent"] = sourceUserAgentProvider()
                    }
                    if (!headersWithUa.containsKeyIgnoreCase("Referer")) {
                        originForReferer(plan.url)?.let { headersWithUa["Referer"] = it }
                    }

                    val sanitizedHeaders = headersWithUa.filterValues(::isHeaderValueSafe).toMutableMap()
                    if (sanitizedHeaders.size != headersWithUa.size) {
                        val dropped = headersWithUa.keys - sanitizedHeaders.keys
                        Log.w(TAG, "Dropping unsafe headers for ${plan.url}: $dropped")
                    }

                    if (plan.useWebView && plan.method == "GET" && plan.type == null) {
                        val effectiveUrl = plan.url
                        logUnimplementedRequestOptions(plan)
                        Log.d(TAG, "Final headers for $effectiveUrl: $sanitizedHeaders")
                        Log.d(TAG, "[WebView] Loading URL: $effectiveUrl")
                        val delayMs = if (plan.webViewDelayTime > 0) plan.webViewDelayTime else 2500L
                        val webViewResult = httpClient.getWithWebView(
                            url = effectiveUrl,
                            headers = sanitizedHeaders,
                            delayMs = delayMs,
                            webJs = plan.webJs,
                            sourceRegex = plan.sourceRegex,
                            overrideUrlRegex = plan.overrideUrlRegex,
                        )
                        if (webViewResult.body.isBlank()) {
                            throw IllegalStateException("Empty WebView response for $effectiveUrl")
                        }
                        return@withLimit LegadoHttpResponse(
                            url = webViewResult.url,
                            body = webViewResult.body,
                            code = webViewResult.code,
                            headers = webViewResult.headers,
                        )
                    }

                    val response = if (plan.method == "POST") {
                        val body = plan.body ?: ""
                        val explicitContentType = sanitizedHeaders.entries
                            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                            ?.value
                        val isJsonBody = body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
                        val charsetSuffix = plan.charsetName
                            ?.takeIf { !it.equals("escape", ignoreCase = true) }
                            ?.let { "; charset=$it" }
                            ?: ""
                        val inferredContentType = when {
                            !explicitContentType.isNullOrBlank() -> explicitContentType
                            isJsonBody -> "application/json$charsetSuffix"
                            plan.bodyIsForm || body.isBlank() -> "application/x-www-form-urlencoded$charsetSuffix"
                            else -> "application/json$charsetSuffix"
                        }
                        if (!sanitizedHeaders.containsKeyIgnoreCase("Content-Type")) {
                            sanitizedHeaders["Content-Type"] = inferredContentType
                        }
                        if (!sanitizedHeaders.containsKeyIgnoreCase("Accept")) {
                            sanitizedHeaders["Accept"] = if (isJsonBody) {
                                "application/json, text/plain, */*"
                            } else {
                                "*/*"
                            }
                        }
                        val effectiveBody = body
                        logUnimplementedRequestOptions(plan)
                        if (BuildConfig.DEBUG) {
                            val bodyPreview = effectiveBody.replace("\r", "").replace("\n", "\\n").take(180)
                            Log.d(
                                TAG,
                                "Final request for ${plan.url}: method=POST contentType=$inferredContentType bodyPreview=$bodyPreview headers=$sanitizedHeaders",
                            )
                        }
                        httpClient.post(
                            plan.url,
                            effectiveBody.toRequestBody(inferredContentType.toMediaTypeOrNull()),
                            sanitizedHeaders,
                            source = source,
                            proxy = plan.proxy,
                            dnsIp = plan.dnsIp,
                            enableCookieJar = plan.enableCookieJar,
                            readTimeoutMs = plan.readTimeoutMs,
                            callTimeoutMs = plan.callTimeoutMs,
                        )
                    } else {
                        val effectiveUrl = plan.url
                        logUnimplementedRequestOptions(plan)
                        Log.d(
                            TAG,
                            "Final request for $effectiveUrl: method=GET headers=$sanitizedHeaders",
                        )
                        httpClient.get(
                            effectiveUrl,
                            sanitizedHeaders,
                            source = source,
                            proxy = plan.proxy,
                            dnsIp = plan.dnsIp,
                            enableCookieJar = plan.enableCookieJar,
                            readTimeoutMs = plan.readTimeoutMs,
                            callTimeoutMs = plan.callTimeoutMs,
                        )
                    }

                    val code = response.code
                    val finalUrl = response.request.url.toString()
                    val responseHeaders = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }
                    if (plan.type != null) {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        response.close()
                        Log.d(TAG, "Binary response for ${plan.url}: code=$code bytes=${bytes.size} type=${plan.type}")
                        return@withLimit LegadoHttpResponse(
                            url = finalUrl,
                            body = bytes.toHexString(),
                            code = code,
                            headers = responseHeaders,
                        )
                    }

                    val content = getResponseBodyWithCharset(response)
                    Log.d(TAG, "Response for ${plan.url}: code=${response.code} contentType=${response.header("Content-Type")}")
                    val responseClone = response.newBuilder().build()
                    response.close()

                    if (content == null) {
                        throw IllegalStateException("Empty response body for ${plan.url}")
                    }

                    val cfStatus = LegadoCloudFlareResolver.checkResponseForProtection(responseClone, content)
                    if (cfStatus == LegadoCloudFlareResolver.PROTECTION_CAPTCHA) {
                        Log.w(TAG, "CF challenge detected, throwing exception for UI verification")
                        throw LegadoCloudFlareResolver.createException(plan.url, source, toHeaders(headersWithUa))
                    } else if (cfStatus == LegadoCloudFlareResolver.PROTECTION_BLOCKED) {
                        Log.e(TAG, "CloudFlare BLOCKED this request!")
                        throw IllegalStateException("CloudFlare blocked request: ${plan.url}")
                    }

                    LegadoHttpResponse(
                        url = finalUrl,
                        body = content,
                        code = code,
                        headers = responseHeaders,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException) throw e

                if (e is org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions) {
                    val waitTime = e.getRetryDelay().coerceAtLeast(1000L)
                    Log.w(TAG, "Rate limit hit (429), waiting ${waitTime}ms before retry $attempt/$maxRetries")
                    delay(waitTime)
                    if (attempt == maxRetries - 1) throw e
                } else {
                    Log.e(TAG, "Request failed: ${plan.url} (attempt $attempt/$maxRetries)", e)
                    if (attempt == maxRetries - 1) throw e
                    delay(500)
                }
            }
        }

        throw IllegalStateException("Request failed after retries: ${plan.url}")
    }

    private fun executeDataUri(plan: LegadoRequestPlan): LegadoHttpResponse {
        val base64Data = DATA_URI_REGEX.find(plan.url)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Unsupported data URI: ${plan.url.take(64)}")
        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(base64Data)
        }.getOrElse {
            Base64.decode(base64Data, Base64.DEFAULT)
        }
        return LegadoHttpResponse(
            url = plan.url,
            body = if (plan.type != null) bytes.toHexString() else bytes.toString(Charsets.UTF_8),
            code = 200,
        )
    }

    private fun MutableMap<String, String>.containsKeyIgnoreCase(key: String): Boolean {
        return keys.any { it.equals(key, ignoreCase = true) }
    }

    private fun mergeHeaders(
        target: MutableMap<String, String>,
        incoming: Map<String, String>,
    ) {
        incoming.forEach { (key, value) ->
            val existingKey = target.keys.find { it.equals(key, ignoreCase = true) }
            if (existingKey != null) {
                target.remove(existingKey)
            }
            target[key] = value
        }
    }

    private fun applyCookieHeaders(
        headers: MutableMap<String, String>,
        plan: LegadoRequestPlan,
    ) {
        val requestCookieKey = headers.keys.find { it.equals("Cookie", ignoreCase = true) }
        val requestCookie = requestCookieKey?.let(headers::get)

        if (!plan.enableCookieJar) {
            if (requestCookieKey != null && requestCookie.isNullOrBlank()) {
                headers.remove(requestCookieKey)
            }
            return
        }

        val persistedCookie = httpClient.getPersistentCookieHeader(plan.url).takeIf { it.isNotBlank() }
        val mergedCookie = mergeCookies(persistedCookie, requestCookie)
        when {
            mergedCookie.isNullOrBlank() && requestCookieKey != null -> headers.remove(requestCookieKey)
            mergedCookie.isNullOrBlank() -> Unit
            requestCookieKey != null -> headers[requestCookieKey] = mergedCookie
            else -> headers["Cookie"] = mergedCookie
        }
    }

    private fun mergeCookies(vararg cookieHeaders: String?): String? {
        val cookieMap = linkedMapOf<String, String>()
        cookieHeaders.filterNotNull().forEach { rawHeader ->
            parseCookieHeader(rawHeader).forEach { (key, value) ->
                cookieMap[key] = value
            }
        }
        if (cookieMap.isEmpty()) return null
        return cookieMap.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun parseCookieHeader(cookieHeader: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        cookieHeader.split(";").forEach { segment ->
            val trimmed = segment.trim()
            if (trimmed.isBlank()) return@forEach
            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.isNotBlank() && (value.isNotBlank() || value == "null")) {
                result[key] = value
            }
        }
        return result
    }

    private fun originForReferer(url: String): String? {
        return runCatching {
            val parsed = java.net.URL(url)
            val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
            "${parsed.protocol}://$host/"
        }.getOrNull()
    }

    private fun isHeaderValueSafe(value: String): Boolean {
        return value.all { ch -> ch == '\t' || ch in '\u0020'..'\u007e' }
    }

    private fun logUnimplementedRequestOptions(plan: LegadoRequestPlan) {
        if (!BuildConfig.DEBUG) return
        if (
            plan.proxy != null ||
            plan.dnsIp != null ||
            plan.serverId != null ||
            plan.bodyJs != null ||
            plan.js != null ||
            plan.readTimeoutMs != null ||
            plan.callTimeoutMs != null
        ) {
            Log.d(
                TAG,
                "Request options status for ${plan.url}: proxy=${plan.proxy ?: "<none>"} dnsIp=${plan.dnsIp ?: "<none>"} serverId=${plan.serverId ?: "<none>"} readTimeoutMs=${plan.readTimeoutMs ?: "<none>"} callTimeoutMs=${plan.callTimeoutMs ?: "<none>"} bodyJsApplied=${!plan.bodyJs.isNullOrBlank()} urlJsApplied=${!plan.js.isNullOrBlank()}",
            )
        }
    }

    private fun ByteArray.toHexString(): String {
        if (isEmpty()) return ""
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            builder.append(((byte.toInt() and 0xff) shr 4).toString(16))
            builder.append((byte.toInt() and 0x0f).toString(16))
        }
        return builder.toString()
    }

    private fun getResponseBodyWithCharset(response: Response): String? {
        val body = response.body ?: return null
        val bytes = body.bytes()
        if (bytes.isEmpty()) return ""

        val encoding = response.header("Content-Encoding")
        var decodedBytes = bytes
        if (encoding != null) {
            try {
                if (encoding.equals("gzip", ignoreCase = true)) {
                    decodedBytes = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).readBytes()
                } else if (encoding.equals("deflate", ignoreCase = true)) {
                    decodedBytes = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(bytes)).readBytes()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual decompression failed: ${e.message}")
            }
        }

        val contentTypeHeader = response.header("Content-Type") ?: ""
        val isJson = contentTypeHeader.contains("json", ignoreCase = true)
        var charset = response.body?.contentType()?.charset()

        if (charset == null) {
            val parts = contentTypeHeader.split(";")
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.startsWith("charset", ignoreCase = true) && trimmed.contains("=")) {
                    val charsetName = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    charset = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrNull()
                    if (charset != null) break
                }
            }
        }

        if (charset == null) {
            charset = if (isJson) {
                Charsets.UTF_8
            } else {
                runCatching { java.nio.charset.Charset.forName(EncodingDetect.getHtmlEncode(decodedBytes)) }
                    .getOrDefault(Charsets.UTF_8)
            }
        }

        Log.d(TAG, "Decoded response using charset: ${charset.name()} (Header: $contentTypeHeader)")
        return String(decodedBytes, charset)
    }

    private fun toHeaders(headersMap: Map<String, String>): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        headersMap.forEach { (key, value) ->
            builder.add(key, value)
        }
        return builder.build()
    }

}
