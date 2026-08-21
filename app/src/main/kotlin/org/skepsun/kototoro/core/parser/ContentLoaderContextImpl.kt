package org.skepsun.kototoro.core.parser

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.util.ext.toList
import org.skepsun.kototoro.core.util.ext.toMimeType
import org.skepsun.kototoro.core.util.ext.use
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.map
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentLoaderContextImpl @Inject constructor(
    @ContentHttpClient override val httpClient: OkHttpClient,
    override val cookieJar: MutableCookieJar,
    @ApplicationContext private val androidContext: Context,
    private val webViewExecutor: WebViewExecutor,
    val webViewRequestInterceptorExecutor: org.skepsun.kototoro.core.network.webview.WebViewRequestInterceptorExecutor,
) : ContentLoaderContext() {

    private val jsTimeout = TimeUnit.SECONDS.toMillis(4)

    override fun newParserInstance(source: ContentSource): org.skepsun.kototoro.parsers.ContentParser {
        val extensionManager = org.skepsun.kototoro.core.extensions.GlobalExtensionManager
        return extensionManager.getContentParser(source, this)
    }

    override fun newLinkResolver(link: okhttp3.HttpUrl): org.skepsun.kototoro.parsers.util.LinkResolver {
        throw UnsupportedOperationException("Link resolution from Kototoro plugins is not supported")
    }

    @Deprecated("Provide a base url")
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = withTimeout(jsTimeout) {
        webViewExecutor.evaluateJs(baseUrl, script)
    }

    override fun getDefaultUserAgent(): String = webViewExecutor.defaultUserAgent ?: UserAgents.FIREFOX_MOBILE

    override fun getConfig(source: ContentSource): ContentSourceConfig {
        return SourceSettings(androidContext, source)
    }

    override fun encodeBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    override fun decodeBase64(data: String): ByteArray {
        return Base64.decode(data, Base64.DEFAULT)
    }

    override fun showToast(message: String, isLong: Boolean) {
        val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(androidContext, message, duration).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(androidContext, message, duration).show()
            }
        }
    }

    fun getSourcePreferences(sourceName: String): SharedPreferences {
        return androidContext.getSharedPreferences(
            sourceName.replace(java.io.File.separatorChar, '$'),
            Context.MODE_PRIVATE,
        )
    }

    override fun getPreferredLocales(): List<Locale> {
        return LocaleListCompat.getAdjustedDefault().toList()
    }

    override fun requestBrowserAction(
        parser: ContentParser,
        url: String,
    ): Nothing = throw InteractiveActionRequiredException(parser.source, url)

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        return response.map { body ->
            BitmapDecoderCompat.decode(body.byteStream(), body.contentType()?.toMimeType(), isMutable = true)
                .use { bitmap ->
                    (redraw(BitmapWrapper.create(bitmap)) as BitmapWrapper).use { result ->
                        Buffer().also {
                            result.compressTo(it.outputStream())
                        }.asResponseBody("image/jpeg".toMediaType())
                    }
                }
        }
    }

    override fun createBitmap(width: Int, height: Int): Bitmap = BitmapWrapper.create(width, height)

    fun loadAssetText(name: String): String {
        return androidContext.assets.open(name).bufferedReader().use { it.readText() }
    }
}
