package org.skepsun.kototoro.core.parser.tsuki

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.core.extensions.JarExtensionLoader
import org.skepsun.kototoro.core.extensions.LoadedJarPlugin
import org.skepsun.kototoro.parsers.ContentLoaderContext
import java.util.Locale

internal class TsukiLoaderContextAdapter(
    private val delegate: ContentLoaderContext,
    private val plugin: LoadedJarPlugin,
) : tsuki.MangaLoaderContext() {

    override val httpClient: OkHttpClient get() = delegate.httpClient
    override val cookieJar: CookieJar get() = delegate.cookieJar

    override fun newParserInstance(source: tsuki.model.MangaSource): tsuki.MangaParser =
        JarExtensionLoader.instantiateTsukiParser(plugin, source, this)

    override fun getParserSources(): List<tsuki.model.MangaSource> =
        plugin.sources.filterIsInstance<tsuki.model.MangaSource>()

    override fun getConfig(source: tsuki.model.MangaSource): tsuki.config.MangaSourceConfig =
        TsukiConfigAdapter(delegate.getConfig(TsukiContentSource(source)))

    override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()
    override fun encodeBase64(data: ByteArray): String = delegate.encodeBase64(data)
    override fun decodeBase64(data: String): ByteArray = delegate.decodeBase64(data)
    override fun getPreferredLocales(): List<Locale> = delegate.getPreferredLocales()

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = delegate.evaluateJs(script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = delegate.evaluateJs(baseUrl, script)

    override fun requestBrowserAction(parser: tsuki.MangaParser, url: String): Nothing {
        throw org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException(
            TsukiContentSource(parser.source),
            url,
        )
    }

    override fun redrawImageResponse(
        response: Response,
        redraw: (image: tsuki.bitmap.Bitmap) -> tsuki.bitmap.Bitmap,
    ): Response = delegate.redrawImageResponse(response) { bitmap ->
        val result = redraw(TsukiBitmapAdapter(bitmap))
        requireNotNull((result as? TsukiBitmapAdapter)?.delegate) {
            "Tsuki image redraw must return a bitmap created by the host context"
        }
    }

    override fun createBitmap(width: Int, height: Int): tsuki.bitmap.Bitmap =
        TsukiBitmapAdapter(delegate.createBitmap(width, height))
}

private class TsukiBitmapAdapter(
    val delegate: org.skepsun.kototoro.parsers.bitmap.Bitmap,
) : tsuki.bitmap.Bitmap {
    override val width: Int get() = delegate.width
    override val height: Int get() = delegate.height

    override fun drawBitmap(
        sourceBitmap: tsuki.bitmap.Bitmap,
        src: tsuki.bitmap.Rect,
        dst: tsuki.bitmap.Rect,
    ) {
        val source = requireNotNull((sourceBitmap as? TsukiBitmapAdapter)?.delegate)
        delegate.drawBitmap(source, src.toKototoro(), dst.toKototoro())
    }
}

private fun tsuki.bitmap.Rect.toKototoro() = org.skepsun.kototoro.parsers.bitmap.Rect(left, top, right, bottom)
