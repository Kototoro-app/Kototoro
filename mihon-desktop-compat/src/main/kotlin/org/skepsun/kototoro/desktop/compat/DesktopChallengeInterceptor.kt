package org.skepsun.kototoro.desktop.compat

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/** Preserve the SDK interceptor identity required by extensions; handle its responses in the next stage. */
/**
 * Without a browser component ([challenges] null) the SDK handler is still kept from running: it would otherwise need a
 * Suwayomi server configuration and fail with a linkage error on the first Cloudflare 403.
 */
internal fun installDesktopChallengeClient(helper: NetworkHelper, challenges: DesktopBrowserChallenges?): OkHttpClient {
    val original = helper.client
    val builder = original.newBuilder()
    val matches = builder.interceptors().withIndex().filter { it.value is CloudflareInterceptor }
    check(matches.size == 1) { "Pinned SDK client must contain exactly one CloudflareInterceptor" }
    builder.interceptors().add(matches.single().index + 1, DesktopChallengeInterceptor(challenges, guardSdkHandler = true))
    val client = builder.build()
    // NetworkHelper is final and exposes no client setter. Keep this version-specific field adapter here;
    // no JAR/bytecode changes, no parallel NetworkHelper, and no mutation of OkHttp's immutable client.
    for (name in listOf("client\$delegate", "cloudflareClient\$delegate")) {
        val field = NetworkHelper::class.java.getDeclaredField(name)
        check(field.type == Lazy::class.java && field.trySetAccessible()) { "Pinned SDK client delegate is unavailable: $name" }
        field.set(helper, lazyOf(client))
    }
    check(helper.client === client && helper.cloudflareClient === client) { "SDK client installation failed" }
    return client
}

internal class DesktopChallengeInterceptor(
    private val challenges: DesktopBrowserChallenges?,
    private val guardSdkHandler: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (challenges == null) {
            val response = chain.proceed(request)
            if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                response.close()
                throw IOException("此来源需要网页验证，但浏览器组件不可用")
            }
            return guarded(response)
        }
        if (chain.call().isCanceled() || challenges.isRequestCancelled()) throw IOException("网页验证请求已取消")
        val response = chain.proceed(request)
        if (!response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return guarded(response)
        if (request.method !in setOf("GET", "HEAD")) {
            response.close()
            throw IOException("此请求需要网页验证；暂不自动重发 ${request.method} 请求")
        }
        response.close()
        challenges.resolve(request, chain.call())
        if (chain.call().isCanceled() || challenges.isRequestCancelled()) throw IOException("网页验证请求已取消")
        // Do not return an unresolved challenge to the SDK's server-only FlareSolverr handler.
        val retried = chain.proceed(request)
        if (retried.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
            retried.close()
            throw IOException("网页验证后仍返回验证页面，请稍后重试")
        }
        return guarded(retried)
    }

    private fun guarded(response: Response): Response {
        // These are the exact status/header pairs recognized by the pinned SDK handler. Ordinary errors do
        // not launch a browser, and must not initialize a Suwayomi server configuration in a desktop session.
        if (guardSdkHandler && response.code in setOf(403, 503) &&
            response.header("Server") in setOf("cloudflare", "cloudflare-nginx")) {
            response.close()
            throw IOException("来源访问被拒绝：HTTP ${response.code}")
        }
        return response
    }
}
