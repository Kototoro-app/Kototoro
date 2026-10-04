package org.skepsun.kototoro.parserhost

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.UserAgents
import java.util.Locale

/** Raised when a parser needs the user to act in a browser (captcha, login); the UI decides how to present it. */
class ParserInteractionRequiredException(val sourceName: String, val url: String) :
    Exception("Source $sourceName needs a browser action")

/**
 * Everything a parser may ask of its host. The embedding application supplies the real implementation:
 * the Windows app shares its OkHttp client and cookie store with the Mihon runtime, so a Cloudflare clearance
 * obtained for one ecosystem is visible to the others, and provides AWT bitmaps and the WebView2 bridge.
 */
interface ParserPlatform {
    val httpClient: OkHttpClient
    val cookieJar: CookieJar

    /** One namespace per source holds its configuration (domain, user agent, toggles). */
    val preferences: SourcePreferenceStore

    val defaultUserAgent: String get() = UserAgents.CHROME_DESKTOP
    val preferredLocales: List<Locale> get() = listOf(Locale.getDefault())

    fun createBitmap(width: Int, height: Int): Bitmap =
        throw UnsupportedOperationException("This host has no bitmap implementation")

    /** Decodes [response], lets [redraw] descramble it and returns a response carrying the result. */
    fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
        throw UnsupportedOperationException("This host has no bitmap implementation")

    suspend fun evaluateJs(baseUrl: String, script: String): String? =
        throw UnsupportedOperationException("This host has no JavaScript engine")

    fun requestBrowserAction(source: ContentSource, url: String): Nothing =
        throw ParserInteractionRequiredException(source.name, url)
}
