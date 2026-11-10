package org.skepsun.kototoro.core.javascript

import okhttp3.Cookie
import okhttp3.RequestBody
import okhttp3.Response
import org.mozilla.javascript.Context as RhinoContext
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.LegadoRuleLog
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import java.io.File
import java.io.InputStream

/**
 * What the Legado script host (`java`, `cookie`, `source`, `book` and the Rhino engine) needs from the application
 * around it. Android answers with its HTTP client, WebView, preferences and activities; a host without one of these
 * capabilities throws [UnsupportedOperationException], which a script sees as a failed call.
 */
interface LegadoScriptPlatform {
    val http: LegadoScriptHttp

    /** The agent a source sends when its own `header` names none. */
    val defaultUserAgent: String

    /** Scripts run in the context this returns, so a host can bound what they reach and how long they run. */
    fun enterRhinoContext(): RhinoContext = RhinoContext.enter()

    /** Cookie access for a script host that was created without its own jar. */
    fun fallbackCookies(): LegadoScriptCookies

    /** A named string store: Android's `legado_source_store` and `legado_book_store` preferences. */
    fun store(name: String): LegadoScriptStore

    /** Where shared `jsLib` downloads are cached. */
    fun cacheDir(): File

    /** The only directory `java.getFile`/`cacheFile`/`downloadFile` may touch; null falls back to the temp directory. */
    fun safeFileRoot(): File?

    fun toast(message: String, long: Boolean)

    fun androidId(): String

    /** Android's `Base64` with its flag bits, used when the standard decoder rejects the input. */
    fun base64Decode(value: String, flags: Int): ByteArray

    fun base64Encode(value: ByteArray, flags: Int): String

    fun t2s(text: String): String

    fun s2t(text: String): String

    fun webViewUserAgent(): String

    fun detectEncoding(bytes: ByteArray): String

    fun unArchive(input: InputStream, outputDir: File)

    fun archiveEntry(input: InputStream, path: String): ByteArray?

    fun queryTtf(font: ByteArray): LegadoQueryTtf

    /** Legado's theme mode: "0" follows the system, "1" is light, "2" is dark. */
    fun themeMode(): String

    fun themeConfig(): Map<String, Any?>

    fun readBookConfig(): Map<String, Any?>

    fun browserMessage(message: LegadoBrowserMessage, detail: String = ""): String

    /** Opens the in-app browser and blocks until the user is done; null when nothing came back. */
    fun startBrowserAwait(
        url: String,
        title: String,
        parserSourceName: String?,
        refetchAfterSuccess: Boolean,
        html: String?,
    ): LegadoBrowserResult?

    fun startBrowser(url: String, title: String, parserSourceName: String?, html: String?)

    /** Handles a `legado://`/`yuedu://` import link; false when [url] is not one. */
    fun openImportUrl(url: String): Boolean

    fun openUrl(url: String, mimeType: String?, parserSourceName: String?, source: LegadoBookSource)
}

/** The requests scripts issue, shaped after Android's `LegadoHttpClient`. */
interface LegadoScriptHttp {
    suspend fun get(url: String): Response

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean,
        readTimeoutMs: Long?,
        callTimeoutMs: Long?,
    ): Response

    suspend fun post(
        url: String,
        body: RequestBody,
        headers: Map<String, String>,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean,
        readTimeoutMs: Long?,
        callTimeoutMs: Long?,
    ): Response

    suspend fun head(url: String, headers: Map<String, String>, callTimeoutMs: Long?): Response

    suspend fun getWithWebView(
        url: String,
        headers: Map<String, String>,
        delayMs: Long,
        webJs: String?,
        sourceRegex: String? = null,
    ): LegadoHttpResponse

    suspend fun loadHtmlWithWebView(html: String, baseUrl: String, delayMs: Long, webJs: String?, userAgent: String?): String?

    suspend fun getWebViewOverrideUrl(
        url: String,
        headers: Map<String, String>,
        delayMs: Long,
        webJs: String?,
        overrideUrlRegex: String,
    ): LegadoHttpResponse?
}

interface LegadoScriptCookies {
    fun getCookieHeader(url: String): String
    fun setCookies(url: String, cookies: List<Cookie>)
    fun removeCookies(url: String)
}

interface LegadoScriptStore {
    fun getString(key: String): String?

    /** A null [value] removes the key. */
    fun putString(key: String, value: String?)
}

/** The glyph lookups `java.replaceFont` needs; scripts still see every method of the host's own font object. */
interface LegadoQueryTtf {
    fun getGlyfIdByUnicode(unicode: Int): Int
    fun getGlyfByUnicode(unicode: Int): String?
    fun getUnicodeByGlyf(glyph: String?): Int
    fun isBlankUnicode(unicode: Int): Boolean
}

class LegadoBrowserResult(val url: String, val html: String)

enum class LegadoBrowserMessage { STARTED, DONE, FAILED }

/** The script host was written against Android's `Log`; this keeps those call sites while logging per platform. */
internal object Log {
    fun d(tag: String, message: String) = LegadoRuleLog.debug(tag, message)
    fun i(tag: String, message: String) = LegadoRuleLog.info(tag, message)
    fun w(tag: String, message: String, cause: Throwable? = null) = LegadoRuleLog.warn(tag, message, cause)
    fun e(tag: String, message: String, cause: Throwable? = null) = LegadoRuleLog.failure(tag, message, cause)
}
