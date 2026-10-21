package org.skepsun.kototoro.ios.compat

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.source.host.HostDomainRewrites

/**
 * Sends requests for an extension's original host to the domain configured in its settings ([HostDomainRewrites]).
 * First in the chain, so cookies, Cloudflare handling and the source's own interceptors all see the new host.
 */
internal class DomainRewriteInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = HostDomainRewrites.target(request.url.host)?.toHttpUrlOrNull() ?: return chain.proceed(request)
        val builder = request.newBuilder().url(request.url.moved(target))
        for (name in listOf("Referer", "Origin")) {
            val value = request.header(name)?.toHttpUrlOrNull() ?: continue
            if (value.host.equals(request.url.host, ignoreCase = true)) {
                builder.header(name, value.moved(target).toString().let { if (name == "Origin") it.trimEnd('/') else it })
            }
        }
        return chain.proceed(builder.build())
    }

    private fun HttpUrl.moved(target: HttpUrl): HttpUrl = newBuilder().scheme(target.scheme).host(target.host)
        .port(target.port).build()
}

/**
 * Puts [DomainRewriteInterceptor] in front of the SDK's shared clients. Extensions use `network.client` directly or
 * derive their own with `newBuilder()`, which keeps the interceptor. NetworkHelper has no setter, so its pinned
 * `client`/`cloudflareClient` lazy delegates are replaced, as the desktop adapter does for its challenge handling.
 */
internal fun installDomainRewrite(helper: NetworkHelper): OkHttpClient {
    val rewritten = HashMap<OkHttpClient, OkHttpClient>()
    fun rewrite(client: OkHttpClient) = rewritten.getOrPut(client) {
        iosNetworkClient(client)
    }
    val client = rewrite(helper.client)
    val cloudflare = rewrite(helper.cloudflareClient)
    for ((name, value) in listOf("client\$delegate" to client, "cloudflareClient\$delegate" to cloudflare)) {
        val field = NetworkHelper::class.java.getDeclaredField(name)
        check(field.type == Lazy::class.java && field.trySetAccessible()) { "Pinned SDK client delegate is unavailable: $name" }
        field.set(helper, lazyOf(value))
    }
    check(helper.client === client && helper.cloudflareClient === cloudflare) { "SDK client installation failed" }
    HostDomainRewrites.installed = true
    return client
}

/** The pinned server interceptor needs FlareSolverr; iOS hands real challenges to the native browser instead. */
internal fun iosNetworkClient(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
    val interceptors = interceptors()
    val index = interceptors.indexOfFirst {
        it.javaClass.name == "eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor"
    }
    check(index >= 0) { "Pinned SDK Cloudflare interceptor is unavailable" }
    interceptors[index] = IosCloudflareInterceptor(client.cookieJar)
    interceptors.add(0, DomainRewriteInterceptor())
}.build()
