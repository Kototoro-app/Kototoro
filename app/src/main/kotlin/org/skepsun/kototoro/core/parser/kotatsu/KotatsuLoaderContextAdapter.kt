package org.skepsun.kototoro.core.parser.kotatsu

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext as KTMangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap as KTBitmap
import org.koitharu.kotatsu.parsers.model.MangaSource as KTContentSource
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import java.util.Locale

/**
 * 将 Kototoro 的 ContentLoaderContext 适配到 kotatsu。
 */
internal class KotatsuLoaderContextAdapter(
    private val delegate: ContentLoaderContext,
) : KTMangaLoaderContext() {

    override val httpClient: OkHttpClient
        get() = delegate.httpClient

    override val cookieJar: CookieJar
        get() = delegate.cookieJar

    override fun newParserInstance(source: KTContentSource): org.koitharu.kotatsu.parsers.MangaParser {
        val extensionManager = org.skepsun.kototoro.core.extensions.GlobalExtensionManager
        return extensionManager.getMangaParser(source, this)
    }

    override fun newLinkResolver(link: okhttp3.HttpUrl): org.koitharu.kotatsu.parsers.util.LinkResolver {
        throw UnsupportedOperationException("Link resolution from Kotatsu plugins is not supported")
    }

    override suspend fun evaluateJs(script: String): String? = delegate.evaluateJs(script)



    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = delegate.evaluateJs(baseUrl, script)

    override fun requestBrowserAction(parser: org.koitharu.kotatsu.parsers.MangaParser, url: String): Nothing {
        val source = KotatsuParserSource(parser.source)
        throw InteractiveActionRequiredException(source, url)
    }

    override fun getConfig(source: KTContentSource): org.koitharu.kotatsu.parsers.config.MangaSourceConfig =
        KotatsuConfigAdapter(delegate.getConfig(KotatsuParserSource(source)))

    override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

    override fun encodeBase64(data: ByteArray): String = delegate.encodeBase64(data)

    override fun decodeBase64(data: String): ByteArray = delegate.decodeBase64(data)

    override fun getPreferredLocales(): List<Locale> = delegate.getPreferredLocales()

    override fun redrawImageResponse(
        response: Response,
        redraw: (image: KTBitmap) -> KTBitmap,
    ): Response = response

    override fun createBitmap(width: Int, height: Int): KTBitmap {
        return object : KTBitmap {
            override val width: Int = width
            override val height: Int = height
            override fun drawBitmap(sourceBitmap: KTBitmap, src: org.koitharu.kotatsu.parsers.bitmap.Rect, dst: org.koitharu.kotatsu.parsers.bitmap.Rect) = Unit
        }
    }

    override suspend fun interceptWebViewRequests(
        url: String,
        config: org.koitharu.kotatsu.parsers.webview.InterceptionConfig
    ): List<org.koitharu.kotatsu.parsers.webview.InterceptedRequest> {
        val impl = delegate as? org.skepsun.kototoro.core.parser.ContentLoaderContextImpl
        return impl?.webViewRequestInterceptorExecutor?.interceptRequests(url, config) ?: emptyList()
    }

    override suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long
    ): List<String> {
        val impl = delegate as? org.skepsun.kototoro.core.parser.ContentLoaderContextImpl
        return impl?.webViewRequestInterceptorExecutor?.captureWebViewUrls(pageUrl, urlPattern, timeout) ?: emptyList()
    }
}
