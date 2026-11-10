package org.skepsun.kototoro.desktop.app

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.skepsun.kototoro.core.javascript.LegadoBrowserMessage
import org.skepsun.kototoro.core.javascript.LegadoBrowserResult
import org.skepsun.kototoro.core.javascript.LegadoQueryTtf
import org.skepsun.kototoro.core.javascript.LegadoScriptCookies
import org.skepsun.kototoro.core.javascript.LegadoScriptHttp
import org.skepsun.kototoro.core.javascript.LegadoScriptPlatform
import org.skepsun.kototoro.core.javascript.LegadoScriptStore
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.parsers.network.UserAgents
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * The desktop side of the shared Legado script host. Book sources are documents from the network, so their scripts
 * run in a Rhino context that only reaches the script API and a fixed set of harmless JDK classes, and that is
 * stopped after [DesktopLegadoRhino.TIMEOUT_MS]. Android capabilities without a desktop counterpart (WebView, the
 * in-app browser, font tables, 7z/rar) fail the script call instead of pretending to work.
 */
internal class DesktopLegadoScriptPlatform(
    private val client: OkHttpClient,
    private val root: Path,
    private val stores: (String) -> SourcePreferences,
    private val message: (String) -> Unit = {},
) : LegadoScriptPlatform {
    private val transport = DesktopLegadoTransport(client)
    override val defaultUserAgent: String get() = UserAgents.CHROME_DESKTOP

    override val http: LegadoScriptHttp = object : LegadoScriptHttp {
        override suspend fun get(url: String): Response = transport.send(Request.Builder().url(url), emptyMap())

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response = transport.send(Request.Builder().url(url), headers, proxy, dnsIp, enableCookieJar, readTimeoutMs, callTimeoutMs)

        override suspend fun post(
            url: String,
            body: RequestBody,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response =
            transport.send(Request.Builder().url(url).post(body), headers, proxy, dnsIp, enableCookieJar, readTimeoutMs, callTimeoutMs)

        override suspend fun head(url: String, headers: Map<String, String>, callTimeoutMs: Long?): Response =
            transport.send(Request.Builder().url(url).head(), headers, callTimeoutMs = callTimeoutMs)

        override suspend fun getWithWebView(
            url: String,
            headers: Map<String, String>,
            delayMs: Long,
            webJs: String?,
            sourceRegex: String?,
        ): LegadoHttpResponse = unsupported("WebView")

        override suspend fun loadHtmlWithWebView(
            html: String,
            baseUrl: String,
            delayMs: Long,
            webJs: String?,
            userAgent: String?,
        ): String? = unsupported("WebView")

        override suspend fun getWebViewOverrideUrl(
            url: String,
            headers: Map<String, String>,
            delayMs: Long,
            webJs: String?,
            overrideUrlRegex: String,
        ): LegadoHttpResponse? = unsupported("WebView")
    }

    override fun enterRhinoContext(): Context = DesktopLegadoRhino.enterContext()

    /** The shared client's own jar, so scripts and requests see the same cookies. */
    override fun fallbackCookies(): LegadoScriptCookies = object : LegadoScriptCookies {
        override fun getCookieHeader(url: String): String {
            val target = url.toHttpUrlOrNull() ?: return ""
            return client.cookieJar.loadForRequest(target).joinToString("; ") { "${it.name}=${it.value}" }
        }

        override fun setCookies(url: String, cookies: List<Cookie>) {
            val target = url.toHttpUrlOrNull() ?: return
            client.cookieJar.saveFromResponse(target, cookies)
        }

        override fun removeCookies(url: String) {
            val target = url.toHttpUrlOrNull() ?: return
            // A CookieJar has no delete; an already expired replacement is how a server removes one too.
            val expired = client.cookieJar.loadForRequest(target).map { cookie ->
                Cookie.Builder().name(cookie.name).value("").path(cookie.path).expiresAt(0L).apply {
                    if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)
                }.build()
            }
            if (expired.isNotEmpty()) client.cookieJar.saveFromResponse(target, expired)
        }
    }

    override fun store(name: String): LegadoScriptStore {
        val preferences = stores(name)
        return object : LegadoScriptStore {
            override fun getString(key: String): String? = (preferences.snapshot()[key] as? SourcePreferenceValue.Text)?.value

            override fun putString(key: String, value: String?) {
                check(preferences.edit(SourcePreferenceEdit(changes = mapOf(key to value?.let(SourcePreferenceValue::Text)))))
            }
        }
    }

    override fun cacheDir(): File = Files.createDirectories(root.resolve("cache")).toFile()

    override fun safeFileRoot(): File = Files.createDirectories(root.resolve("files")).toFile()

    override fun toast(message: String, long: Boolean) = message(message)

    /** Sources use it as an install identifier; the desktop has no such value to hand out. */
    override fun androidId(): String = ""

    override fun base64Decode(value: String, flags: Int): ByteArray =
        (if (flags and URL_SAFE != 0) Base64.getUrlDecoder() else Base64.getMimeDecoder()).decode(value.trim())

    override fun base64Encode(value: ByteArray, flags: Int): String {
        val encoder = if (flags and URL_SAFE != 0) Base64.getUrlEncoder() else Base64.getEncoder()
        return (if (flags and NO_PADDING != 0) encoder.withoutPadding() else encoder).encodeToString(value)
    }

    override fun t2s(text: String): String = unsupported("Chinese script conversion")

    override fun s2t(text: String): String = unsupported("Chinese script conversion")

    override fun webViewUserAgent(): String = defaultUserAgent

    /** What the document declares, else UTF-8; Android additionally guesses from the bytes. */
    override fun detectEncoding(bytes: ByteArray): String = transport.detectHtmlEncoding(bytes)

    override fun unArchive(input: InputStream, outputDir: File) {
        val target = outputDir.toPath().toAbsolutePath().normalize()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val file = target.resolve(entry.name).normalize()
                require(file.startsWith(target)) { "Archive entry leaves its directory: ${entry.name}" }
                if (entry.isDirectory) Files.createDirectories(file) else {
                    Files.createDirectories(file.parent)
                    Files.copy(zip, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override fun archiveEntry(input: InputStream, path: String): ByteArray? = ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
    }

    override fun queryTtf(font: ByteArray): LegadoQueryTtf = unsupported("font glyph lookup")

    override fun themeMode(): String = "0"

    override fun themeConfig(): Map<String, Any?> = emptyMap()

    override fun readBookConfig(): Map<String, Any?> = emptyMap()

    override fun browserMessage(message: LegadoBrowserMessage, detail: String): String = when (message) {
        LegadoBrowserMessage.STARTED -> AndroidStrings["legado_browser_verification_started"]
        LegadoBrowserMessage.DONE -> AndroidStrings["legado_browser_verification_done"]
        LegadoBrowserMessage.FAILED -> AndroidStrings["legado_browser_verification_failed"].replace("%s", detail)
            .replace("%1\$s", detail)
    }

    override fun startBrowserAwait(
        url: String,
        title: String,
        parserSourceName: String?,
        refetchAfterSuccess: Boolean,
        html: String?,
    ): LegadoBrowserResult? = unsupported("browser verification")

    override fun startBrowser(url: String, title: String, parserSourceName: String?, html: String?): Unit =
        unsupported("the in-app browser")

    override fun openImportUrl(url: String): Boolean = false

    override fun openUrl(url: String, mimeType: String?, parserSourceName: String?, source: LegadoBookSource): Unit =
        unsupported("opening links")

    private fun unsupported(what: String): Nothing =
        throw UnsupportedOperationException("Legado scripts cannot use $what on the desktop")

    private companion object {
        const val NO_PADDING = 1
        const val URL_SAFE = 8
    }
}

/** Thrown past the engine's own error handling, so a runaway script ends the source operation instead of a rule. */
internal class DesktopLegadoScriptTimeout : Error("Legado script timed out")

/** Every Legado script context of the desktop: interpreted, time-bounded and confined by [DesktopLegadoClassShutter]. */
internal object DesktopLegadoRhino : ContextFactory() {
    /** Generous because `java.ajax` and friends block inside the script while the clock runs. */
    const val TIMEOUT_MS = 30_000L
    @Volatile var timeoutMillis = TIMEOUT_MS

    private class TimedContext(factory: ContextFactory, val deadline: Long) : Context(factory)

    override fun makeContext(): Context = TimedContext(this, System.nanoTime() + timeoutMillis * 1_000_000L).apply {
        // The instruction observer only runs in interpreted mode.
        optimizationLevel = -1
        instructionObserverThreshold = 10_000
        setClassShutter(DesktopLegadoClassShutter)
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() > (cx as TimedContext).deadline) {
            throw DesktopLegadoScriptTimeout()
        }
    }
}

/**
 * Android lets scripts reach any class because the app sandbox contains them; a desktop process has the user's whole
 * account. Scripts therefore see the script API, value types, text, crypto and HTML helpers — nothing that opens
 * files, processes, class loaders or reflection. An object of any other class still passes through a script, but only
 * as an opaque `Object`.
 */
internal object DesktopLegadoClassShutter : ClassShutter {
    private val classes = setOf(
        "java.lang.Object", "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.CharSequence",
        "java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Short",
        "java.lang.Byte", "java.lang.Boolean", "java.lang.Character", "java.lang.Math", "java.lang.Iterable",
        "java.lang.Comparable", "java.lang.Throwable",
        "java.net.URLEncoder", "java.net.URLDecoder", "java.net.URI",
        "java.nio.charset.Charset", "java.nio.charset.StandardCharsets",
        "java.security.MessageDigest", "java.security.KeyFactory", "java.security.Signature", "java.security.SecureRandom",
        "java.security.Key", "java.security.PublicKey", "java.security.PrivateKey",
        "kotlin.Pair", "kotlin.Triple", "kotlin.Unit", "kotlin.text.Regex", "kotlin.text.MatchResult",
        "org.skepsun.kototoro.core.javascript.LegadoJavaAPI", "org.skepsun.kototoro.core.javascript.LegadoCookieAPI",
        "org.skepsun.kototoro.core.javascript.StrResponse", "org.skepsun.kototoro.core.javascript.BookInfo",
        "org.skepsun.kototoro.core.javascript.ChapterInfo", "org.skepsun.kototoro.core.javascript.LegadoQueryTtf",
        "org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleJavaBridge",
        "org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext\$CacheBinding",
    )
    private val prefixes = listOf(
        "java.text.", "java.math.", "java.util.", "java.security.spec.", "javax.crypto.", "kotlin.collections.",
        "org.jsoup.", "org.json.", "okhttp3.", "org.skepsun.kototoro.core.model.jsonsource.",
        "org.skepsun.kototoro.core.javascript.LegadoJavaAPI\$", "org.skepsun.kototoro.core.javascript.RhinoJavaScriptEngine\$",
        "org.skepsun.kototoro.core.parser.legado.AnalyzeRule\$", "org.skepsun.kototoro.core.parser.legado.RuleData",
    )
    private val denied = listOf(
        "java.util.concurrent.", "java.util.jar.", "java.util.zip.", "java.util.logging.", "java.util.prefs.", "java.util.spi.",
        "java.util.stream.", "java.util.ServiceLoader", "java.util.Formatter", "java.util.Scanner", "java.util.Timer",
        "java.util.Properties", "java.util.ResourceBundle", "java.util.PropertyResourceBundle",
    )

    override fun visibleToScripts(fullClassName: String): Boolean {
        if (fullClassName in classes) return true
        if (fullClassName.startsWith("java.lang.") && fullClassName.endsWith("Exception")) return true
        return prefixes.any(fullClassName::startsWith) && denied.none(fullClassName::startsWith)
    }
}
