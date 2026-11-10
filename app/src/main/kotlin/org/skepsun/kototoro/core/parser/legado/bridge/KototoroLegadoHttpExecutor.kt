package org.skepsun.kototoro.core.parser.legado.bridge

import android.util.Base64
import okhttp3.RequestBody
import okhttp3.Response
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.legado.ConcurrentRateLimiter
import org.skepsun.kototoro.core.parser.legado.LegadoCloudFlareResolver
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoPlanHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoPlanTransport
import org.skepsun.kototoro.core.util.EncodingDetect
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

/**
 * Kototoro 对 LegadoRequestPlan 的默认执行器：请求规则在 `:source-legado` 的 [LegadoPlanHttpExecutor]，
 * 这里只提供 Android 的网络、WebView 与 Cloudflare 处理。
 */
class KototoroLegadoHttpExecutor(
    private val source: ContentSource,
    config: LegadoBookSource,
    private val httpClient: LegadoHttpClient,
    rateLimiter: ConcurrentRateLimiter,
    configHeadersProvider: () -> Map<String, String>,
    loginHeadersProvider: () -> Map<String, String>,
    sourceUserAgentProvider: () -> String,
) : LegadoHttpExecutor by LegadoPlanHttpExecutor(
    config = config,
    httpClient = AndroidTransport(source, httpClient),
    rateLimiter = rateLimiter,
    configHeadersProvider = configHeadersProvider,
    loginHeadersProvider = loginHeadersProvider,
    sourceUserAgentProvider = sourceUserAgentProvider,
) {

    private class AndroidTransport(
        private val source: ContentSource,
        private val httpClient: LegadoHttpClient,
    ) : LegadoPlanTransport {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response = httpClient.get(
            url,
            headers,
            source = source,
            proxy = proxy,
            dnsIp = dnsIp,
            enableCookieJar = enableCookieJar,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        )

        override suspend fun post(
            url: String,
            body: RequestBody,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response = httpClient.post(
            url,
            body,
            headers,
            source = source,
            proxy = proxy,
            dnsIp = dnsIp,
            enableCookieJar = enableCookieJar,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        )

        override suspend fun getWithWebView(
            url: String,
            headers: Map<String, String>,
            delayMs: Long,
            webJs: String?,
            sourceRegex: String?,
            overrideUrlRegex: String?,
        ): LegadoHttpResponse = httpClient.getWithWebView(
            url = url,
            headers = headers,
            delayMs = delayMs,
            webJs = webJs,
            sourceRegex = sourceRegex,
            overrideUrlRegex = overrideUrlRegex,
        )

        override fun persistentCookieHeader(url: String): String = httpClient.getPersistentCookieHeader(url)

        override fun beforeRequest(cookieSyncUrl: String) {
            LegadoCloudFlareResolver.syncCloudFlareCookies(cookieSyncUrl)
        }

        override fun checkProtection(response: Response, finalUrl: String, requestHeaders: Map<String, String>) {
            when (CloudFlareHelper.checkResponseForProtection(response)) {
                CloudFlareHelper.PROTECTION_CAPTCHA -> {
                    response.close()
                    android.util.Log.w(TAG, "CF challenge detected, throwing exception for UI verification")
                    throw org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException(
                        url = CloudFlareHelper.getChallengeUrl(finalUrl),
                        source = source,
                        headers = toHeaders(requestHeaders),
                    )
                }

                CloudFlareHelper.PROTECTION_BLOCKED -> {
                    response.close()
                    android.util.Log.e(TAG, "CloudFlare blocked this request")
                    throw org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException(
                        url = finalUrl,
                        source = source,
                    )
                }
            }
        }

        override fun isProtectionError(error: Exception): Boolean =
            error is org.skepsun.kototoro.core.exceptions.CloudFlareException

        override fun detectHtmlEncoding(bytes: ByteArray): String = EncodingDetect.getHtmlEncode(bytes)

        override fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

        override val verboseLogging: Boolean get() = BuildConfig.DEBUG

        private fun toHeaders(headersMap: Map<String, String>): okhttp3.Headers {
            val builder = okhttp3.Headers.Builder()
            headersMap.forEach { (key, value) ->
                builder.add(key, value)
            }
            return builder.build()
        }
    }

    companion object {
        private const val TAG = "KototoroLegadoHttpExecutor"

        fun parseHeaderJson(raw: String?): Map<String, String> = LegadoPlanHttpExecutor.parseHeaderJson(raw)
    }
}
