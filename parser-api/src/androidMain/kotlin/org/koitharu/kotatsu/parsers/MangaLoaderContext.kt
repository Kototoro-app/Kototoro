package org.koitharu.kotatsu.parsers

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import org.koitharu.kotatsu.parsers.util.LinkResolver
import java.util.*

public abstract class MangaLoaderContext {

	public abstract val httpClient: OkHttpClient

	public abstract val cookieJar: CookieJar

	public abstract fun newParserInstance(source: MangaSource): MangaParser

	public abstract fun newLinkResolver(link: HttpUrl): LinkResolver

	public fun newLinkResolver(link: String): LinkResolver = newLinkResolver(link.toHttpUrl())

	public open fun encodeBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

	public open fun decodeBase64(data: String): ByteArray = Base64.getDecoder().decode(data)

	public open fun getPreferredLocales(): List<Locale> = listOf(Locale.getDefault())

	/**
	 * Execute JavaScript code and return result
	 * @param script JavaScript source code
	 * @return execution result as string, may be null
	 */
	@Deprecated("Provide a base url")
	public abstract suspend fun evaluateJs(script: String): String?

	/**
	 * Execute JavaScript code and return result
	 * @param script JavaScript source code
	 * @param baseUrl url of page script will be executed in context of
	 * @return execution result as string, may be null
	 */
	public abstract suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String?

	/**
	 * Open [url] in browser for some external action (e.g. captcha solving or non cookie-based authorization)
	 */
	public open fun requestBrowserAction(parser: MangaParser, url: String): Nothing {
		throw UnsupportedOperationException("Browser is not available")
	}

	public abstract fun getConfig(source: MangaSource): MangaSourceConfig

	public open fun getDefaultUserAgent(): String = org.koitharu.kotatsu.parsers.network.UserAgents.CHROME_MOBILE

	/**
	 * Helper function to be used in an interceptor
	 * to descramble images
	 * @param response Image response
	 * @param redraw lambda function to implement descrambling logic
	 */
	public abstract fun redrawImageResponse(
		response: Response,
		redraw: (image: Bitmap) -> Bitmap,
	): Response

	/**
	 * create a new empty Bitmap with given dimensions
	 */
	public abstract fun createBitmap(
		width: Int,
		height: Int,
	): Bitmap

    /**
     * Intercept WebView requests with custom filtering logic
     * Loads the specified URL in a WebView and captures HTTP requests that match the filter criteria.
     *
     * @param url The URL to load in the WebView
     * @param interceptorScript JavaScript code that returns true/false for requests to capture
     * @param timeout Maximum time to wait for requests (milliseconds)
     * @return List of intercepted requests matching the filter criteria
     */
    public open suspend fun interceptWebViewRequests(
        url: String,
        interceptorScript: String,
        timeout: Long = 30000L
    ): List<InterceptedRequest> {
        throw UnsupportedOperationException("WebView request interception is not available")
    }

    /**
     * Intercept WebView requests with advanced configuration
     * Loads the specified URL in a WebView and captures HTTP requests that match the filter criteria.
     * Supports separate page script injection and request filtering.
     *
     * @param url The URL to load in the WebView
     * @param config Configuration including page script, filter script, timeout, etc.
     * @return List of intercepted requests matching the filter criteria
     */
    public open suspend fun interceptWebViewRequests(
        url: String,
        config: InterceptionConfig
    ): List<InterceptedRequest> {
        // Fallback to the simple version for backward compatibility
        val script = config.filterScript ?: "return true;"
        return interceptWebViewRequests(url, script, config.timeoutMs)
    }

    /**
     * Simplified API for capturing WebView URLs matching a pattern
     *
     * @param pageUrl The URL to load in the WebView
     * @param urlPattern Regex pattern to match against request URLs
     * @param timeout Maximum time to wait for requests (milliseconds)
     * @return List of URLs that matched the pattern
     */
    public open suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long = 30000L
    ): List<String> {
        throw UnsupportedOperationException("WebView URL capture is not available")
    }
}
