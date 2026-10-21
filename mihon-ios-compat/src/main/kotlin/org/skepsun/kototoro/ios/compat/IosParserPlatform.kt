package org.skepsun.kototoro.ios.compat

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.core.source.SourceBrowserSession
import org.skepsun.kototoro.parserhost.ParserPlatform
import org.skepsun.kototoro.parsers.network.UserAgents

/**
 * Parser plugins run on the Mobile API's network client, so cookies (and any clearance) are shared with Mihon /
 * Aniyomi sources. Bitmap descrambling and JavaScript are not available in this JVM yet; parsers needing them fail
 * with the base [ParserPlatform] errors rather than silently returning wrong images.
 */
internal class IosParserPlatform(
    private val client: OkHttpClient,
    override val preferences: SourcePreferenceStore,
) : ParserPlatform {
    override val httpClient: OkHttpClient get() = client
    override val cookieJar: CookieJar get() = client.cookieJar
    // Use the real engine's default on iOS; explicit per-source User-Agent headers still take precedence.
    override val defaultUserAgent: String by lazy {
        if (IosWebKit.isAvailable) {
            runCatching { IosWebKit.command("userAgent.default") }.getOrNull()?.takeIf(String::isNotBlank)
                ?: UserAgents.CHROME_MOBILE
        } else {
            UserAgents.CHROME_MOBILE
        }
    }

    override fun browserSession(url: String, userAgent: String): SourceBrowserSession =
        client.interceptors.filterIsInstance<IosCloudflareInterceptor>().firstOrNull()?.session(url)
            ?: super.browserSession(url, userAgent)
}
