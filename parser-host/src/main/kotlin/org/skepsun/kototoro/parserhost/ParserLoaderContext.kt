package org.skepsun.kototoro.parserhost

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.LinkResolver
import java.util.Locale

/** The Kototoro-ABI context every plugin architecture ultimately talks to; Kotatsu and Tsuki wrap it. */
internal class ParserLoaderContext(
    private val platform: ParserPlatform,
    private val siblingParser: (String) -> ContentParser,
) : ContentLoaderContext() {

    override val httpClient: OkHttpClient get() = platform.httpClient
    override val cookieJar: CookieJar get() = platform.cookieJar

    override fun newParserInstance(source: ContentSource): ContentParser = siblingParser(source.name)

    override fun newLinkResolver(link: HttpUrl): LinkResolver =
        throw UnsupportedOperationException("Link resolution from parser plugins is not supported")

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = platform.evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = platform.evaluateJs(baseUrl, script)

    override fun getConfig(source: ContentSource): ContentSourceConfig = config(source)

    fun config(source: ContentSource) = ParserSourceConfig(platform.preferences.open(namespace(source.name)))

    override fun getDefaultUserAgent(): String = platform.defaultUserAgent

    override fun getPreferredLocales(): List<Locale> = platform.preferredLocales

    override fun requestBrowserAction(parser: ContentParser, url: String): Nothing = browserAction(parser.source, url)

    fun browserAction(source: ContentSource, url: String): Nothing = platform.requestBrowserAction(source, url)

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response =
        platform.redrawImageResponse(response, redraw)

    override fun createBitmap(width: Int, height: Int): Bitmap = platform.createBitmap(width, height)

    companion object {
        fun namespace(sourceName: String) = "parser_source:$sourceName"
    }
}
