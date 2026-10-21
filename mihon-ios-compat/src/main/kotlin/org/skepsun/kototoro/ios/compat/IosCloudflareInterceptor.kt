package org.skepsun.kototoro.ios.compat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Request
import okhttp3.CookieJar
import org.skepsun.kototoro.core.source.SourceBrowserSession
import org.skepsun.kototoro.core.source.SourceChallengeException
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Replaces the server-only FlareSolverr handler with iOS's native browser handoff. */
internal class IosCloudflareInterceptor(
    cookieJar: CookieJar = CookieJar.NO_COOKIES,
    private val browserFetch: (Request) -> Response? = { browserHttpResponse(it, cookieJar) },
) : Interceptor {
    private val sessions = ConcurrentHashMap<String, SourceBrowserSession>()

    fun session(url: String): SourceBrowserSession? = url.toHttpUrlOrNull()?.let { sessions[it.host] }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_CAPTCHA) return response
        val request = response.request
        sessions[chain.request().url.host] = SourceBrowserSession(request.url.toString(), request.header("User-Agent"))
        response.close()
        if (request.method in setOf("GET", "HEAD") && !chain.call().isCanceled()) {
            val browser = browserFetch(request)
            if (browser != null) {
                if (chain.call().isCanceled()) {
                    browser.close()
                    throw IOException("Canceled")
                }
                if (CloudFlareHelper.checkResponseForProtection(browser) == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                    return browser
                }
                browser.close()
            }
        }
        throw IOException("Browser verification required", SourceChallengeException(request.url.toString()))
    }
}
