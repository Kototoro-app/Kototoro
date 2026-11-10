package org.skepsun.kototoro.core.javascript

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import okhttp3.Cookie
import okhttp3.RequestBody
import okhttp3.Response
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.OpenUrlConfirmActivity
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import org.skepsun.kototoro.core.network.jsonsource.UserAgentManager
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ChineseConverter
import org.skepsun.kototoro.core.util.EncodingDetect
import org.skepsun.kototoro.core.util.LibArchiveUtils
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesActivity
import java.io.File
import java.io.InputStream
import java.net.CookieManager
import java.util.Locale

/** Android's answers for the shared Legado script host in `:source-legado`. */
class AndroidLegadoScriptPlatform(
    private val httpClient: LegadoHttpClient,
    private val context: Context,
    private val cookieJar: PersistentCookieJar? = null,
    browserLauncherFactory: (Context, PersistentCookieJar?) -> BrowserLauncher = ::BrowserLauncher,
) : LegadoScriptPlatform {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val browserLauncher = browserLauncherFactory(context, cookieJar)

    override val http: LegadoScriptHttp = object : LegadoScriptHttp {
        override suspend fun get(url: String): Response = httpClient.get(url)

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response = httpClient.get(
            url = url,
            headers = headers,
            source = null,
            proxy = proxy,
            dnsIp = dnsIp,
            enableCookieJar = enableCookieJar,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        )

        override suspend fun post(
            url: String,
            body: RequestBody,
            headers: Map<String, String>,
            proxy: String?,
            dnsIp: String?,
            enableCookieJar: Boolean,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
        ): Response = httpClient.post(
            url = url,
            body = body,
            headers = headers,
            source = null,
            proxy = proxy,
            dnsIp = dnsIp,
            enableCookieJar = enableCookieJar,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        )

        override suspend fun head(url: String, headers: Map<String, String>, callTimeoutMs: Long?): Response =
            httpClient.head(url = url, headers = headers, callTimeoutMs = callTimeoutMs)

        override suspend fun getWithWebView(
            url: String,
            headers: Map<String, String>,
            delayMs: Long,
            webJs: String?,
            sourceRegex: String?,
        ): LegadoHttpResponse = httpClient.getWithWebView(
            url = url,
            headers = headers,
            delayMs = delayMs,
            webJs = webJs,
            sourceRegex = sourceRegex,
        )

        override suspend fun loadHtmlWithWebView(
            html: String,
            baseUrl: String,
            delayMs: Long,
            webJs: String?,
            userAgent: String?,
        ): String? = httpClient.loadHtmlWithWebView(
            html = html,
            baseUrl = baseUrl,
            delayMs = delayMs,
            webJs = webJs,
            userAgent = userAgent,
        )

        override suspend fun getWebViewOverrideUrl(
            url: String,
            headers: Map<String, String>,
            delayMs: Long,
            webJs: String?,
            overrideUrlRegex: String,
        ): LegadoHttpResponse? = httpClient.getWebViewOverrideUrl(
            url = url,
            headers = headers,
            delayMs = delayMs,
            webJs = webJs,
            overrideUrlRegex = overrideUrlRegex,
        )
    }

    override val defaultUserAgent: String get() = UserAgentManager().getUserAgent()

    override fun fallbackCookies(): LegadoScriptCookies =
        AndroidLegadoScriptCookies(cookieJar ?: PersistentCookieJar(httpClient.getCookieJar()))

    override fun store(name: String): LegadoScriptStore {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return object : LegadoScriptStore {
            override fun getString(key: String): String? = prefs.getString(key, null)

            override fun putString(key: String, value: String?) {
                prefs.edit().apply {
                    if (value == null) remove(key) else putString(key, value)
                }.apply()
            }
        }
    }

    override fun cacheDir(): File = context.cacheDir

    override fun safeFileRoot(): File? {
        fun pathToFile(path: String?): File? = path?.takeIf { it.isNotBlank() }?.let(::File)
        return pathToFile(runCatching { context.externalCacheDir?.absolutePath }.getOrNull())
            ?: pathToFile(runCatching { context.cacheDir?.absolutePath }.getOrNull())
    }

    override fun toast(message: String, long: Boolean) {
        mainHandler.post {
            Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    override fun base64Decode(value: String, flags: Int): ByteArray = android.util.Base64.decode(value, flags)

    override fun base64Encode(value: ByteArray, flags: Int): String = android.util.Base64.encodeToString(value, flags)

    override fun t2s(text: String): String = ChineseConverter.t2s(text)

    override fun s2t(text: String): String = ChineseConverter.s2t(text)

    override fun webViewUserAgent(): String = android.webkit.WebSettings.getDefaultUserAgent(context)

    override fun detectEncoding(bytes: ByteArray): String = EncodingDetect.getEncode(bytes)

    override fun unArchive(input: InputStream, outputDir: File) {
        LibArchiveUtils.unArchive(input, outputDir)
    }

    override fun archiveEntry(input: InputStream, path: String): ByteArray? = LibArchiveUtils.getByteArrayContent(input, path)

    override fun queryTtf(font: ByteArray): LegadoQueryTtf = QueryTTF(font)

    override fun themeMode(): String {
        return when (AppSettings(context).theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> "1"
            AppCompatDelegate.MODE_NIGHT_YES -> "2"
            else -> "0"
        }
    }

    override fun themeConfig(): Map<String, Any?> {
        val settings = AppSettings(context)
        val isNightTheme = settings.theme == AppCompatDelegate.MODE_NIGHT_YES
        val accentColor = settings.colorScheme.name.lowercase(Locale.ROOT)
        val backgroundColor = if (isNightTheme) "#121212" else "#FFFFFF"
        val bottomBackground = if (settings.isAmoledTheme && isNightTheme) "#000000" else backgroundColor
        return linkedMapOf(
            "themeName" to settings.colorScheme.name,
            "isNightTheme" to isNightTheme,
            "primaryColor" to backgroundColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "backgroundImgPath" to null,
            "backgroundImgBlur" to 0,
        )
    }

    override fun readBookConfig(): Map<String, Any?> {
        val settings = AppSettings(context)
        val novel = NovelReaderSettings.load(context)
        val isNightTheme = settings.theme == AppCompatDelegate.MODE_NIGHT_YES
        val textColor = if (isNightTheme) "#ADADAD" else "#3E3D3B"
        val backgroundColor = when (settings.readerBackground) {
            org.skepsun.kototoro.core.prefs.ReaderBackground.BLACK -> "#000000"
            org.skepsun.kototoro.core.prefs.ReaderBackground.DARK -> "#000000"
            org.skepsun.kototoro.core.prefs.ReaderBackground.WHITE -> "#FFFFFF"
            else -> "#EEEEEE"
        }
        return linkedMapOf(
            "name" to novel.themePreset.name,
            "bgStr" to backgroundColor,
            "bgStrNight" to "#000000",
            "bgStrEInk" to "#FFFFFF",
            "bgAlpha" to 100,
            "bgType" to 0,
            "bgTypeNight" to 0,
            "bgTypeEInk" to 0,
            "darkStatusIcon" to !isNightTheme,
            "darkStatusIconNight" to false,
            "darkStatusIconEInk" to true,
            "textColor" to textColor,
            "textColorNight" to "#ADADAD",
            "textColorEInk" to "#000000",
            "textColorInt" to 0,
            "textColorIntNight" to 0,
            "textColorIntEInk" to 0,
            "textAccentColor" to textColor,
            "textAccentColorNight" to "#FE4D55",
            "textAccentColorEInk" to "#000000",
            "textAccentColorInt" to 0,
            "textAccentColorIntNight" to 0,
            "textAccentColorIntEInk" to 0,
            "pageAnim" to if (novel.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) 1 else 0,
            "pageAnimEInk" to 4,
            "textFont" to "",
            "titleFont" to "",
            "headerFont" to "",
            "footerFont" to "",
            "headerFontSize" to 12,
            "footerFontSize" to 12,
            "textBold" to 500,
            "textSize" to novel.fontSizeSp.toInt(),
            "letterSpacing" to 0.1f,
            "lineSpacingExtra" to (novel.lineSpacing * 10).toInt(),
            "paragraphSpacing" to novel.paragraphSpacing.toInt(),
            "titleMode" to 0,
            "titleSize" to 0,
            "titleTopSpacing" to 0,
            "titleBottomSpacing" to 0,
            "titleColor" to 0,
            "paragraphIndent" to if (novel.enableParagraphIndent) "　　" else "",
            "paddingBottom" to 6,
            "paddingLeft" to novel.marginHorizontal,
            "paddingRight" to novel.marginHorizontal,
            "paddingTop" to 6,
            "headerPaddingBottom" to 0,
            "headerPaddingLeft" to novel.marginHorizontal,
            "headerPaddingRight" to novel.marginHorizontal,
            "headerPaddingTop" to 0,
            "footerPaddingBottom" to 6,
            "footerPaddingLeft" to novel.marginHorizontal,
            "footerPaddingRight" to novel.marginHorizontal,
            "footerPaddingTop" to 6,
            "showHeaderLine" to false,
            "showFooterLine" to novel.showReadingStatus,
            "tipHeaderLeft" to 0,
            "tipHeaderMiddle" to 0,
            "tipHeaderRight" to 0,
            "tipFooterLeft" to 0,
            "tipFooterMiddle" to 0,
            "tipFooterRight" to 0,
            "tipHeaderColor" to 0,
            "tipFooterColor" to 0,
            "tipDividerColor" to -1,
            "headerMode" to 0,
            "footerMode" to 0,
            "regexColorRules" to emptyList<Map<String, Any?>>(),
        )
    }

    override fun browserMessage(message: LegadoBrowserMessage, detail: String): String = when (message) {
        LegadoBrowserMessage.STARTED -> context.getString(R.string.legado_browser_verification_started)
        LegadoBrowserMessage.DONE -> context.getString(R.string.legado_browser_verification_done)
        LegadoBrowserMessage.FAILED -> context.getString(R.string.legado_browser_verification_failed, detail)
    }

    override fun startBrowserAwait(
        url: String,
        title: String,
        parserSourceName: String?,
        refetchAfterSuccess: Boolean,
        html: String?,
    ): LegadoBrowserResult? = browserLauncher.launchAndWait(
        url = url,
        title = title,
        source = parserSourceName?.let { ContentSource(it) },
        refetchAfterSuccess = refetchAfterSuccess,
        html = html,
    )?.let { LegadoBrowserResult(it.url, it.html) }

    override fun startBrowser(url: String, title: String, parserSourceName: String?, html: String?) {
        val intent = AppRouter.browserIntent(
            context = context,
            url = url,
            source = parserSourceName?.let { ContentSource(it) },
            title = title,
        ).putExtra(AppRouter.KEY_BROWSER_HTML, html)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun openImportUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "legado" && scheme != "yuedu") {
            return false
        }
        val importUrl = uri.getQueryParameter("src").orEmpty().ifBlank { return false }
        val intent = UnifiedSourcesActivity.newIntent(
            context = context,
            initialRepositoryKind = UnifiedSourceKind.LEGADO,
            initialRepositoryUrl = importUrl,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    override fun openUrl(url: String, mimeType: String?, parserSourceName: String?, source: LegadoBookSource) {
        val activity = requireNotNull(context as? AppCompatActivity) { "openUrl activity context required" }
        val sourceOrigin = parserSourceName?.let { ContentSource(it) }?.name ?: source.bookSourceUrl
        val intent = OpenUrlConfirmActivity.newIntent(
            activity = activity,
            url = url,
            mimeType = mimeType,
            sourceOrigin = sourceOrigin,
            sourceName = source.bookSourceName,
            sourceType = LEGADO_SOURCE_TYPE_BOOK,
        )
        context.startActivity(intent)
    }

    private companion object {
        private const val LEGADO_SOURCE_TYPE_BOOK = 0
    }
}

/** Keeps the jar's own lookups (domain folding, header length cap) behind the shared cookie port. */
class AndroidLegadoScriptCookies(private val jar: PersistentCookieJar) : LegadoScriptCookies {
    override fun getCookieHeader(url: String): String = jar.getCookieHeader(url)
    override fun setCookies(url: String, cookies: List<Cookie>) = jar.setCookies(url, cookies)
    override fun removeCookies(url: String) = jar.removeCookies(url)
}

/** The Android wiring of the shared engine: every existing call site keeps constructing it from app services. */
@Suppress("FunctionName", "UNUSED_PARAMETER")
fun RhinoJavaScriptEngine(
    httpClient: LegadoHttpClient,
    cookieManager: CookieManager,
    cookieJar: PersistentCookieJar,
    androidContext: Context,
): RhinoJavaScriptEngine = RhinoJavaScriptEngine(
    AndroidLegadoScriptPlatform(httpClient, androidContext, cookieJar),
    AndroidLegadoScriptCookies(cookieJar),
)

@Suppress("FunctionName", "UNUSED_PARAMETER")
fun LegadoJavaAPI(
    httpClient: LegadoHttpClient,
    cookieManager: CookieManager,
    context: Context,
    cookieJar: PersistentCookieJar? = null,
    browserLauncherFactory: (Context, PersistentCookieJar?) -> BrowserLauncher = ::BrowserLauncher,
): LegadoJavaAPI = LegadoJavaAPI(
    AndroidLegadoScriptPlatform(httpClient, context, cookieJar, browserLauncherFactory),
    cookieJar?.let(::AndroidLegadoScriptCookies),
)

@Suppress("FunctionName")
fun LegadoCookieAPI(cookieJar: PersistentCookieJar): LegadoCookieAPI = LegadoCookieAPI(AndroidLegadoScriptCookies(cookieJar))
