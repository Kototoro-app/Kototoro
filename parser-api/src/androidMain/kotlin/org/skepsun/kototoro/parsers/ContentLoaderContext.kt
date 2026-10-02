package org.skepsun.kototoro.parsers

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.LinkResolver
import java.util.*

public abstract class ContentLoaderContext {

	public abstract val httpClient: OkHttpClient

	public abstract val cookieJar: CookieJar

	public abstract fun newParserInstance(source: ContentSource): ContentParser

	public abstract fun newLinkResolver(link: HttpUrl): LinkResolver

	public fun newLinkResolver(link: String): LinkResolver = newLinkResolver(link.toHttpUrl())

	public open fun encodeBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

	public open fun decodeBase64(data: String): ByteArray = Base64.getDecoder().decode(data)

	public open fun getPreferredLocales(): List<Locale> = listOf(Locale.getDefault())

	/**
	 * Optional user-facing notification, default no-op for non-Android environments.
	 */
	public open fun showToast(message: String, isLong: Boolean = false) {}

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
	public abstract suspend fun evaluateJs(baseUrl: String, script: String): String?

	/**
	 * Open [url] in browser for some external action (e.g. captcha solving or non cookie-based authorization)
	 */
	public open fun requestBrowserAction(parser: ContentParser, url: String): Nothing {
		throw UnsupportedOperationException("Browser is not available")
	}

	public abstract fun getConfig(source: ContentSource): ContentSourceConfig

	public open fun getDefaultUserAgent(): String = UserAgents.CHROME_MOBILE

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
}
