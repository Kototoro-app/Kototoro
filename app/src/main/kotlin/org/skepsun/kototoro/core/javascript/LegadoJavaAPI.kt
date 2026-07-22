package org.skepsun.kototoro.core.javascript

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64 as AndroidBase64
import android.util.LruCache
import androidx.appcompat.app.AppCompatDelegate
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.legado.ConcurrentRateLimiter
import org.skepsun.kototoro.core.parser.legado.AnalyzeByJSoup
import org.skepsun.kototoro.core.parser.legado.AnalyzeByJsonPath
import org.skepsun.kototoro.core.parser.legado.LegadoRequestPlanBuilder
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ChineseConverter
import org.skepsun.kototoro.core.util.LibArchiveUtils
import java.net.CookieManager
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.skepsun.kototoro.browser.OpenUrlConfirmActivity
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesActivity
import androidx.appcompat.app.AppCompatActivity

/**
 * Legado Java API 实现
 * 
 * 提供 Legado 兼容的 Java API，供 JavaScript 代码调用
 * 参考 Legado 源码: app/src/main/java/io/legado/app/help/http/HttpHelper.kt
 */
class LegadoJavaAPI(
    private val httpClient: LegadoHttpClient,
    private val cookieManager: CookieManager,
    private val context: Context,
    private val cookieJar: org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar? = null,
    private val browserLauncherFactory: (Context, org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar?) -> BrowserLauncher =
        ::BrowserLauncher,
) {
    
    private var currentHtml: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val browserLauncher = browserLauncherFactory(context, cookieJar)
    
    // 存储 JavaScript 变量的 Map
    private val jsVariables = mutableMapOf<String, Any?>()
    
    // JavaScript 上下文引用（由 RhinoJavaScriptEngine 设置）
    var jsContext: JavaScriptContext? = null
    
    companion object {
        private const val TAG = "LegadoJavaAPI"
        private const val ARCHIVE_TEMP_FOLDER_NAME = "ArchiveTemp"
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_DATE_TIME_FORMAT = "yyyy/MM/dd HH:mm"
        private const val LEGADO_SOURCE_TYPE_BOOK = 0
        private const val QUERY_TTF_CACHE_SIZE = 16
        private val TITLE_NUM_PATTERN = Regex("(第)(.+?)(章)")
        private val queryTtfCache = LruCache<String, QueryTTF>(QUERY_TTF_CACHE_SIZE)
        private val CHINESE_NUMBER_MAP: Map<Char, Int> = buildMap {
            "零一二三四五六七八九十".forEachIndexed { index, c -> put(c, index) }
            "〇壹贰叁肆伍陆柒捌玖拾".forEachIndexed { index, c -> put(c, index) }
            put('两', 2)
            put('百', 100)
            put('佰', 100)
            put('千', 1000)
            put('仟', 1000)
            put('万', 10000)
            put('亿', 100000000)
        }

        private fun resolveKeyAlgorithm(transformation: String): String {
            return transformation.substringBefore('/').substringBefore("with").ifBlank { "RSA" }
        }

        private fun resolveKeyAlgorithmForSignature(algorithm: String): String {
            val upper = algorithm.uppercase(Locale.ROOT)
            return when {
                upper.endsWith("RSA") -> "RSA"
                upper.endsWith("DSA") -> "DSA"
                upper.endsWith("ECDSA") || upper.endsWith("EC") -> "EC"
                else -> "RSA"
            }
        }

        private fun normalizeKeyBytes(raw: ByteArray): ByteArray {
            val text = raw.toString(Charsets.UTF_8).trim()
            if (text.startsWith("-----BEGIN")) {
                val base64 = text
                    .lineSequence()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
                    .trim()
                return java.util.Base64.getDecoder().decode(base64)
            }
            if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
                runCatching { return java.util.Base64.getDecoder().decode(text) }
                runCatching { return java.util.Base64.getUrlDecoder().decode(text) }
            }
            return raw
        }

        private fun normalizeBinaryInput(data: Any, decodeStringInput: Boolean = true): ByteArray {
            return when (data) {
                is ByteArray -> data
                is InputStream -> data.readBytes()
                is String -> {
                    if (!decodeStringInput) {
                        data.toByteArray(Charsets.UTF_8)
                    } else {
                        val trimmed = data.trim()
                        when {
                            trimmed.length % 2 == 0 && trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
                                trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            else -> runCatching { java.util.Base64.getDecoder().decode(trimmed) }
                                .recoverCatching { java.util.Base64.getUrlDecoder().decode(trimmed) }
                                .getOrElse { data.toByteArray(Charsets.UTF_8) }
                        }
                    }
                }
                else -> data.toString().toByteArray(Charsets.UTF_8)
            }
        }
    }

    @Suppress("unused")
    fun getSource(): RhinoJavaScriptEngine.SourceWrapper? {
        val source = jsContext?.source ?: return null
        return RhinoJavaScriptEngine.SourceWrapper(
            source = source,
            context = context,
            cookieJar = cookieJar ?: return null,
            jsExecutor = { script, scriptContext ->
                val activeCookieJar = cookieJar ?: return@SourceWrapper null
                RhinoJavaScriptEngine(
                    httpClient = httpClient,
                    cookieManager = cookieManager,
                    cookieJar = activeCookieJar,
                    androidContext = context,
                ).execute(script, scriptContext)
            },
            contextFactory = { extraBindings ->
                JavaScriptContext(
                    baseUrl = jsContext?.baseUrl,
                    book = jsContext?.book,
                    chapter = jsContext?.chapter,
                    source = jsContext?.source,
                    sourceName = jsContext?.sourceName,
                    runtimeContext = jsContext?.runtimeContext,
                    key = jsContext?.key,
                    page = jsContext?.page,
                    result = jsContext?.result,
                ).also { nestedContext ->
                    jsContext?.getAllVariables()?.forEach { (name, value) ->
                        nestedContext.setVariable(name, value)
                    }
                    extraBindings.forEach { (name, value) ->
                        nestedContext.setVariable(name, value)
                    }
                }
            },
        )
    }

    @Suppress("unused")
    fun getTag(): String? {
        return jsContext?.source?.bookSourceName
    }

    private fun resolveParserContentSource(): ContentSource? {
        val sourceName = jsContext?.sourceName
            ?: jsContext?.runtimeContext?.getParserSourceName()
        return sourceName?.takeIf { it.isNotBlank() }?.let { org.skepsun.kototoro.core.model.ContentSource(it) }
    }

    private val jsEvaluator = LegadoJsEvaluator { script, result ->
        evaluateInlineJs(script, result)
    }
    
    /**
     * 执行 HTTP 请求 (GET)
     * 
     * @param url 请求 URL
     * @return 响应内容
     */
    fun ajax(url: String): String {
        return ajax(url, emptyMap())
    }

    @Suppress("unused")
    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    @Suppress("unused")
    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = when (url) {
            is List<*> -> url.firstOrNull()?.toString().orEmpty()
            else -> url.toString()
        }
        return try {
            executeRequest(urlStr, null, callTimeoutMs = callTimeout).body()
        } catch (e: Exception) {
            Log.e(TAG, "Ajax request failed: $urlStr - ${e.javaClass.simpleName}: ${e.message}")
            e.stackTraceToString()
        }
    }

    /**
     * Rhino 兼容：部分脚本会调用 java.ajax(url, undefined/null)。
     *
     * Rhino 传入的第二参数可能是 `org.mozilla.javascript.Undefined` 或 `NativeObject`，
     * 这里做一次兜底分发，避免 `Can't find method ...ajax(string, Undefined)`。
     */
    @Suppress("unused")
    fun ajax(url: String, options: Any?): String {
        if (options == null || options is org.mozilla.javascript.Undefined) {
            return ajax(url, emptyMap())
        }
        val mapOptions = when (options) {
            is Map<*, *> -> options.entries.associate { it.key.toString() to it.value }
            is org.mozilla.javascript.NativeObject -> {
                val result = LinkedHashMap<String, Any?>()
                options.ids.forEach { key ->
                    val k = key?.toString() ?: return@forEach
                    result[k] = options.get(k, options)
                }
                result
            }
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        return ajax(url, mapOptions as? Map<String, Any>)
    }
    
    /**
     * 执行 HTTP 请求 (支持 GET/POST)
     * 
     * @param url 请求 URL
     * @param options 请求选项（可选）
     *   - method: 请求方法 (GET/POST)
     *   - headers: 请求头
     *   - body: 请求体
     *   - charset: 字符编码
     * @return 响应内容
     */
    fun ajax(url: String, options: Map<String, Any>?): String {
        return try {
            executeRequest(url, options).body()
        } catch (e: Exception) {
            Log.e(TAG, "Ajax request failed: $url - ${e.javaClass.simpleName}: ${e.message}")
            ""
        }
    }

    private fun parseInlineOptions(url: String): Pair<String, Map<String, Any>?> {
        val trimmed = url.trim()
        val commaIndex = trimmed.indexOf(",{").let { if (it >= 0) it else trimmed.indexOf(", {") }
        if (commaIndex < 0) return trimmed to null

        val urlPart = trimmed.substring(0, commaIndex).trim()
        val jsonPart = trimmed.substring(commaIndex + 1).trim()
        return try {
            val obj = org.json.JSONObject(jsonPart)
            val map = LinkedHashMap<String, Any>()
            obj.keys().forEach { key ->
                map[key] = obj.get(key)
            }
            urlPart to map
        } catch (_: Exception) {
            urlPart to null
        }
    }
    
    /**
     * 使用 CSS 选择器解析 HTML
     * 
     * @param selector CSS 选择器
     * @return 匹配的元素列表
     */
    fun getElement(selector: String): Elements {
        Log.d(TAG, "getElement called with selector: $selector")
        val html = currentHtml ?: run {
            Log.e(TAG, "getElement: No HTML content set!")
            throw IllegalStateException("No HTML content set. Call setContent() first.")
        }

        val doc = Jsoup.parse(html)
        val result = AnalyzeByJSoup(doc).getElements(selector)
        Log.d(TAG, "getElement($selector) found ${result.size} elements via AnalyzeByJSoup")

        val elements = Elements()
        result.forEach { item ->
            when (item) {
                is Element -> elements.add(item)
                is org.jsoup.nodes.TextNode -> { item.parent()?.let { elements.add(it) } }
                is List<*> -> item.forEach { inner ->
                    if (inner is Element) elements.add(inner)
                }
            }
        }
        if (elements.isEmpty() && result.isNotEmpty()) {
            Log.w(TAG, "getElement: result not convertable to Elements, first item type=${result.firstOrNull()?.javaClass?.simpleName}")
        }

        jsContext?.setVariable("lastSelector", selector)
        put("lastSelector", selector)

        return elements
    }

    fun getElements(selector: String): Elements {
        Log.d(TAG, "getElements called with selector: $selector")
        return getElement(selector)
    }
    
    /**
     * 转换 Legado 选择器到 Jsoup 选择器
     */
    private fun convertLegadoSelectorToJsoup(selector: String): String {
        return when {
            selector.startsWith("@css:") -> selector.removePrefix("@css:")
            selector.startsWith("class.") -> ".${selector.removePrefix("class.")}"
            selector.startsWith("id.") -> "#${selector.removePrefix("id.")}"
            selector.startsWith("tag.") -> selector.removePrefix("tag.")
            else -> selector
        }
    }
    
    /**
     * 设置当前 HTML 内容
     * 
     * @param html HTML 字符串
     */
    fun setContent(html: String) {
        currentHtml = html
    }
    
    /**
     * 设置当前 HTML 内容 (支持多种类型)
     * 
     * @param content 内容对象 (可以是 String, Element, 或其他对象的 outerHtml/toString)
     */
    fun setContent(content: Any?) {
        currentHtml = when (content) {
            null -> null
            is String -> content
            is Element -> content.outerHtml()
            is org.mozilla.javascript.NativeJavaObject -> {
                val unwrapped = content.unwrap()
                when (unwrapped) {
                    is String -> unwrapped
                    is Element -> unwrapped.outerHtml()
                    else -> unwrapped?.toString()
                }
            }
            else -> content.toString()
        }
        Log.d(TAG, "setContent: type=${content?.javaClass?.simpleName}, html=${currentHtml?.take(100)}")
    }
    
    /**
     * Base64 编码
     * 
     * @param str 要编码的字符串
     * @return Base64 编码后的字符串
     */
    fun base64Encode(str: String): String? {
        return base64Encode(str, AndroidBase64.NO_WRAP)
    }

    @Suppress("unused")
    fun base64Encode(str: String, flags: Int): String? {
        val encoder = when {
            flags and AndroidBase64.URL_SAFE != 0 -> java.util.Base64.getUrlEncoder()
            else -> java.util.Base64.getEncoder()
        }
        val normalizedEncoder = if (flags and AndroidBase64.NO_PADDING != 0) {
            encoder.withoutPadding()
        } else {
            encoder
        }
        return runCatching {
            normalizedEncoder.encodeToString(str.toByteArray())
        }.getOrElse {
            runCatching { AndroidBase64.encodeToString(str.toByteArray(), flags) }.getOrNull()
        }
    }
    
    /**
     * Base64 解码
     * 
     * @param str Base64 编码的字符串
     * @return 解码后的字符串
     */
    fun base64Decode(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return runCatching {
            String(base64DecodeBytes(str, AndroidBase64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun base64Decode(str: String?, charset: String): String {
        if (str.isNullOrBlank()) return ""
        val resolvedCharset = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        return runCatching {
            String(base64DecodeBytes(str, AndroidBase64.DEFAULT), resolvedCharset)
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun base64Decode(str: String, flags: Int): String {
        return runCatching {
            String(base64DecodeBytes(str, flags), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) return null
        return runCatching { base64DecodeBytes(str, AndroidBase64.DEFAULT) }.getOrNull()
    }

    @Suppress("unused")
    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) return null
        return runCatching { base64DecodeBytes(str, flags) }.getOrNull()
    }
    
    /**
     * 十六进制字符串解码为普通字符串
     * 
     * @param hex 十六进制字符串
     * @return 解码后的字符串
     */
    fun hexDecodeToString(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes)
    }

    @Suppress("unused")
    fun hexDecodeToByteArray(hex: String): ByteArray? {
        return runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull()
    }

    fun hexEncode(str: String): String {
        return str.toByteArray().joinToString("") { "%02x".format(it) }
    }

    @Suppress("unused")
    fun hexEncodeToString(utf8: String): String {
        return hexEncode(utf8)
    }

    @Suppress("unused")
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?,
    ): SymmetricCryptoCompat {
        return SymmetricCryptoCompat(transformation, key, iv)
    }

    @Suppress("unused")
    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray,
    ): SymmetricCryptoCompat {
        return createSymmetricCrypto(transformation, key, null)
    }

    @Suppress("unused")
    fun createSymmetricCrypto(
        transformation: String,
        key: String,
    ): SymmetricCryptoCompat {
        return createSymmetricCrypto(transformation, key, null)
    }

    @Suppress("unused")
    fun createSymmetricCrypto(
        transformation: String,
        key: String,
        iv: String?,
    ): SymmetricCryptoCompat {
        return createSymmetricCrypto(
            transformation = transformation,
            key = key.toByteArray(Charsets.UTF_8),
            iv = iv?.toByteArray(Charsets.UTF_8),
        )
    }

    @Suppress("unused")
    fun createAsymmetricCrypto(
        transformation: String,
    ): AsymmetricCryptoCompat {
        return AsymmetricCryptoCompat(transformation)
    }

    @Suppress("unused")
    fun createSign(
        algorithm: String,
    ): SignCompat {
        return SignCompat(algorithm)
    }

    @Suppress("unused")
    fun aesDecodeToByteArray(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): ByteArray? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decrypt(str) }.getOrNull()
    }

    @Suppress("unused")
    fun aesDecodeToString(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(str) }.getOrNull()
    }

    @Suppress("unused")
    fun aesDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching {
            createSymmetricCrypto(
                "AES/$mode/$padding",
                java.util.Base64.getDecoder().decode(key),
                java.util.Base64.getDecoder().decode(iv),
            ).decryptStr(data)
        }.getOrNull()
    }

    @Suppress("unused")
    fun aesBase64DecodeToByteArray(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): ByteArray? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decrypt(str) }.getOrNull()
    }

    @Suppress("unused")
    fun aesBase64DecodeToString(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(str) }.getOrNull()
    }

    @Suppress("unused")
    fun aesEncodeToByteArray(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): ByteArray? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).encrypt(data) }.getOrNull()
    }

    @Suppress("unused")
    fun aesEncodeToString(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(data) }.getOrNull()
    }

    @Suppress("unused")
    fun aesEncodeToBase64ByteArray(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): ByteArray? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).encryptBase64(data).toByteArray() }.getOrNull()
    }

    @Suppress("unused")
    fun aesEncodeToBase64String(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).encryptBase64(data) }.getOrNull()
    }

    @Suppress("unused")
    fun aesEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching {
            createSymmetricCrypto(
                "AES/$mode/$padding",
                java.util.Base64.getDecoder().decode(key),
                java.util.Base64.getDecoder().decode(iv),
            ).encryptBase64(data)
        }.getOrNull()
    }

    @Suppress("unused")
    fun desDecodeToString(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(data) }.getOrNull()
    }

    @Suppress("unused")
    fun desBase64DecodeToString(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(data) }.getOrNull()
    }

    @Suppress("unused")
    fun desEncodeToString(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { String(createSymmetricCrypto(transformation, key, iv).encrypt(data), Charsets.UTF_8) }.getOrNull()
    }

    @Suppress("unused")
    fun desEncodeToBase64String(
        data: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto(transformation, key, iv).encryptBase64(data) }.getOrNull()
    }

    @Suppress("unused")
    fun tripleDESDecodeStr(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto("DESede/$mode/$padding", key, iv).decryptStr(data) }.getOrNull()
    }

    @Suppress("unused")
    fun tripleDESDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching {
            createSymmetricCrypto(
                "DESede/$mode/$padding",
                java.util.Base64.getDecoder().decode(key),
                iv.toByteArray(Charsets.UTF_8),
            ).decryptStr(data)
        }.getOrNull()
    }

    @Suppress("unused")
    fun tripleDESEncodeBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching { createSymmetricCrypto("DESede/$mode/$padding", key, iv).encryptBase64(data) }.getOrNull()
    }

    @Suppress("unused")
    fun tripleDESEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String,
    ): String? {
        return runCatching {
            createSymmetricCrypto(
                "DESede/$mode/$padding",
                java.util.Base64.getDecoder().decode(key),
                iv.toByteArray(Charsets.UTF_8),
            ).encryptBase64(data)
        }.getOrNull()
    }
    
    /**
     * 时间格式化
     * 
     * @param timestamp 时间戳（毫秒）
     * @param format 格式字符串 (如 "yyyy-MM-dd HH:mm:ss")
     * @param timezone 时区 (如 "GMT+8")
     * @return 格式化后的时间字符串
     */
    fun timeFormat(timestamp: Long): String {
        return SimpleDateFormat(DEFAULT_DATE_TIME_FORMAT, Locale.getDefault()).format(Date(timestamp))
    }

    fun timeFormat(timestamp: Long, format: String, timezone: String? = null): String {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        if (timezone != null) {
            sdf.timeZone = TimeZone.getTimeZone(timezone)
        }
        return sdf.format(Date(timestamp))
    }
    
    /**
     * UTC 时间格式化
     * 
     * @param timestamp 时间戳（毫秒）
     * @param format 格式字符串
     * @param offset UTC 偏移量（小时）
     * @return 格式化后的时间字符串
     */
    fun timeFormatUTC(timestamp: Long, format: String, offset: Int = 0): String {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        sdf.timeZone = SimpleTimeZone(offset, "UTC")
        return sdf.format(Date(timestamp))
    }
    
    /**
     * 显示 Toast 提示
     * 
     * @param message 提示消息
     */
    fun toast(message: Any?) {
        val displayMessage = formatSourceScopedMessage(message)
        mainHandler.post {
            Toast.makeText(context, displayMessage, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 显示长时间 Toast 提示
     * 
     * @param message 提示消息
     */
    fun longToast(message: Any?) {
        val displayMessage = formatSourceScopedMessage(message)
        mainHandler.post {
            Toast.makeText(context, displayMessage, Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 获取设备的 Android ID
     * 
     * @return Android ID
     */
    fun androidId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }
    
    /**
     * 繁体转简体
     */
    fun t2s(text: String): String {
        return ChineseConverter.t2s(text)
    }

    /**
     * 简体转繁体
     */
    fun s2t(text: String): String {
        return ChineseConverter.s2t(text)
    }

    private fun base64DecodeBytes(str: String, flags: Int): ByteArray {
        val decoder = when {
            flags and AndroidBase64.URL_SAFE != 0 -> java.util.Base64.getUrlDecoder()
            else -> java.util.Base64.getDecoder()
        }
        return runCatching {
            decoder.decode(str)
        }.getOrElse {
            AndroidBase64.decode(str, flags)
        }
    }

    class SymmetricCryptoCompat(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?,
    ) {
        private val normalizedTransformation = normalizeTransformation(transformation)
        private val algorithm = normalizedTransformation.substringBefore('/')
        private val secretKey = SecretKeySpec(normalizeKey(key, algorithm), algorithm)
        private val ivSpec = normalizeIv(iv, algorithm)?.let(::IvParameterSpec)

        fun decrypt(data: String): ByteArray {
            return doFinal(Cipher.DECRYPT_MODE, decodeCipherInput(data))
        }

        fun decryptStr(data: String): String {
            return String(decrypt(data), Charsets.UTF_8)
        }

        fun encrypt(data: String): ByteArray {
            return encrypt(data.toByteArray(Charsets.UTF_8))
        }

        fun encrypt(data: ByteArray): ByteArray {
            return doFinal(Cipher.ENCRYPT_MODE, data)
        }

        fun encryptBase64(data: String): String {
            return java.util.Base64.getEncoder().encodeToString(encrypt(data))
        }

        fun encryptBase64(data: ByteArray): String {
            return java.util.Base64.getEncoder().encodeToString(encrypt(data))
        }

        fun encryptHex(data: String): String {
            return encrypt(data).joinToString("") { "%02x".format(it) }
        }

        fun encryptHex(data: ByteArray): String {
            return encrypt(data).joinToString("") { "%02x".format(it) }
        }

        private fun doFinal(mode: Int, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(normalizedTransformation)
            if (ivSpec != null && !normalizedTransformation.contains("/ECB/", ignoreCase = true)) {
                cipher.init(mode, secretKey, ivSpec)
            } else {
                cipher.init(mode, secretKey)
            }
            return cipher.doFinal(data)
        }

        private fun decodeCipherInput(data: String): ByteArray {
            val trimmed = data.trim()
            return if (trimmed.length % 2 == 0 && trimmed.isNotEmpty() && trimmed.all(::isHexChar)) {
                trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                java.util.Base64.getDecoder().decode(trimmed)
            }
        }

        private fun isHexChar(char: Char): Boolean {
            return char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }

        private companion object {
            fun normalizeTransformation(value: String): String {
                val trimmed = value.trim()
                val renamed = when {
                    trimmed.equals("3DES", ignoreCase = true) -> "DESede"
                    trimmed.equals("TripleDES", ignoreCase = true) -> "DESede"
                    trimmed.startsWith("3DES/", ignoreCase = true) -> "DESede/" + trimmed.substringAfter('/')
                    trimmed.startsWith("TripleDES/", ignoreCase = true) -> "DESede/" + trimmed.substringAfter('/')
                    else -> trimmed
                }.replace("PKCS7Padding", "PKCS5Padding", ignoreCase = true)

                return if ('/' !in renamed) {
                    when {
                        renamed.equals("AES", ignoreCase = true) -> "AES/ECB/PKCS5Padding"
                        renamed.equals("DES", ignoreCase = true) -> "DES/ECB/PKCS5Padding"
                        renamed.equals("DESede", ignoreCase = true) -> "DESede/ECB/PKCS5Padding"
                        else -> renamed
                    }
                } else {
                    renamed
                }
            }

            fun normalizeKey(key: ByteArray?, algorithm: String): ByteArray {
                val raw = key ?: ByteArray(0)
                val targetSize = when (algorithm.uppercase(Locale.ROOT)) {
                    "AES" -> when {
                        raw.size <= 16 -> 16
                        raw.size <= 24 -> 24
                        else -> 32
                    }
                    "DES" -> 8
                    "DESEDE" -> 24
                    else -> raw.size.coerceAtLeast(1)
                }
                return raw.copyOf(targetSize)
            }

            fun normalizeIv(iv: ByteArray?, algorithm: String): ByteArray? {
                val raw = iv?.takeIf { it.isNotEmpty() } ?: return null
                val blockSize = when (algorithm.uppercase(Locale.ROOT)) {
                    "AES" -> 16
                    "DES", "DESEDE" -> 8
                    else -> raw.size
                }
                return raw.copyOf(blockSize)
            }
        }
    }

    class AsymmetricCryptoCompat(
        private val transformation: String,
    ) {
        private val keyAlgorithm = resolveKeyAlgorithm(transformation)
        private var publicKey: PublicKey? = null
        private var privateKey: PrivateKey? = null

        fun setPublicKey(key: ByteArray): AsymmetricCryptoCompat {
            publicKey = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(normalizeKeyBytes(key)))
            return this
        }

        fun setPublicKey(key: String): AsymmetricCryptoCompat {
            return setPublicKey(key.toByteArray(Charsets.UTF_8))
        }

        fun setPrivateKey(key: ByteArray): AsymmetricCryptoCompat {
            privateKey = KeyFactory.getInstance(keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(normalizeKeyBytes(key)))
            return this
        }

        fun setPrivateKey(key: String): AsymmetricCryptoCompat {
            return setPrivateKey(key.toByteArray(Charsets.UTF_8))
        }

        fun decrypt(data: Any, usePublicKey: Boolean? = true): ByteArray {
            return doCipher(
                mode = Cipher.DECRYPT_MODE,
                key = if (usePublicKey != false) publicKey else privateKey,
                data = normalizeBinaryInput(data),
            )
        }

        fun decryptStr(data: Any, usePublicKey: Boolean? = true): String {
            return String(decrypt(data, usePublicKey), Charsets.UTF_8)
        }

        fun encrypt(data: Any, usePublicKey: Boolean? = true): ByteArray {
            return doCipher(
                mode = Cipher.ENCRYPT_MODE,
                key = if (usePublicKey != false) publicKey else privateKey,
                data = normalizeBinaryInput(data, decodeStringInput = false),
            )
        }

        fun encryptBase64(data: Any, usePublicKey: Boolean? = true): String {
            return java.util.Base64.getEncoder().encodeToString(encrypt(data, usePublicKey))
        }

        fun encryptHex(data: Any, usePublicKey: Boolean? = true): String {
            return encrypt(data, usePublicKey).joinToString("") { "%02x".format(it) }
        }

        private fun doCipher(mode: Int, key: java.security.Key?, data: ByteArray): ByteArray {
            require(key != null) { "Missing ${if (mode == Cipher.ENCRYPT_MODE) "encryption" else "decryption"} key for $transformation" }
            val cipher = Cipher.getInstance(transformation)
            cipher.init(mode, key)
            return cipher.doFinal(data)
        }
    }

    class SignCompat(
        private val algorithm: String,
    ) {
        private val keyAlgorithm = resolveKeyAlgorithmForSignature(algorithm)
        private var publicKey: PublicKey? = null
        private var privateKey: PrivateKey? = null

        fun setPublicKey(key: ByteArray): SignCompat {
            publicKey = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(normalizeKeyBytes(key)))
            return this
        }

        fun setPublicKey(key: String): SignCompat {
            return setPublicKey(key.toByteArray(Charsets.UTF_8))
        }

        fun setPrivateKey(key: ByteArray): SignCompat {
            privateKey = KeyFactory.getInstance(keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(normalizeKeyBytes(key)))
            return this
        }

        fun setPrivateKey(key: String): SignCompat {
            return setPrivateKey(key.toByteArray(Charsets.UTF_8))
        }

        fun sign(data: Any): ByteArray {
            val key = requireNotNull(privateKey) { "Missing private key for $algorithm" }
            val signature = Signature.getInstance(algorithm)
            signature.initSign(key)
            signature.update(normalizeBinaryInput(data, decodeStringInput = false))
            return signature.sign()
        }

        fun signHex(data: Any): String {
            return sign(data).joinToString("") { "%02x".format(it) }
        }
    }

    
    /**
     * 从当前 HTML 内容中提取字符串
     * 
     * @param rule Legado 规则表达式 (如 "tag.a@href", "class.title@text", "#id@src")
     * @return 提取的字符串值
     */
    fun getString(rule: String): String {
        val content = currentHtml
        if (content.isNullOrEmpty()) {
            Log.w(TAG, "getString($rule): currentHtml is null/empty")
            return ""
        }

        val trimmedRule = rule.trim()
        val trimmedContent = content.trimStart()
        val isJsonContent = trimmedContent.startsWith("{") || trimmedContent.startsWith("[")
        val isJsonRule =
            trimmedRule.startsWith("$") ||
                trimmedRule.startsWith("@json:", ignoreCase = true) ||
                (isJsonContent && trimmedRule.startsWith("."))

        try {
            // legado-with-MD3 兼容：在 JSON 内容上允许直接使用 JsonPath（例如 $.data.list[0].catid）
            if (isJsonRule) {
                val jsonRule = when {
                    trimmedRule.startsWith("@json:", ignoreCase = true) -> trimmedRule.substring(6).trim()
                    trimmedRule.startsWith(".") -> "\$$trimmedRule"
                    else -> trimmedRule
                }
                return AnalyzeByJsonPath(content).getString(jsonRule).orEmpty()
            }

            // HTML：使用 legado 规则语法（包含 `@` 链式、`&&/||/%%`）
            return AnalyzeByJSoup(content).getString0(rule)
        } catch (e: Exception) {
            Log.e(TAG, "getString($rule) failed: ${e.message}")
            return ""
        }
    }

    /**
     * 获取字符串列表（legado 兼容）
     *
     * - HTML：按 legado 规则语法（包含 `@` 链式、`&&/||/%%`）通过 JSoup 解析
     * - JSON：按 JsonPath 解析（支持 `$`/`.` 形式）
     */
    fun getStringList(rule: String): List<String> {
        return getStringList(rule, null)
    }

    fun getStringList(rule: String, content: String?): List<String> {
        val text = content ?: currentHtml
        if (rule.isBlank() || text.isNullOrBlank()) return emptyList()

        val trimmedText = text.trimStart()
        val trimmedRule = rule.trim()
        val isJson = trimmedRule.startsWith("$") ||
            trimmedRule.startsWith("@json:", ignoreCase = true) ||
            trimmedText.startsWith("{") ||
            trimmedText.startsWith("[")

        return try {
            if (isJson) {
                val jsonPathRule = normalizeToJsonPath(trimmedRule)
                AnalyzeByJsonPath(text).getStringList(jsonPathRule)
            } else {
                AnalyzeByJSoup(text).getStringList(trimmedRule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStringList(rule=$trimmedRule) failed: ${e.message}")
            emptyList()
        }
    }

    private fun normalizeToJsonPath(rule: String): String {
        val trimmed = rule.trim()
        if (trimmed.startsWith("@json:", ignoreCase = true)) {
            return trimmed.substringAfter(":").trim()
        }
        if (trimmed.startsWith("$")) return trimmed
        if (trimmed.startsWith(".")) return "$$trimmed"
        return "$.$trimmed"
    }
    
    /**
     * 从上下文中获取指定键的值
     * 
     * 支持从 JavaScript 变量存储和上下文中获取值
     * 
     * @param key 键名
     * @return 值
     */
    fun get(key: String): Any? {
        val runtimeContext = jsContext?.runtimeContext
        when (key) {
            "title" -> runtimeContext?.getChapter()?.name?.takeIf { it.isNotEmpty() }?.let { return it }
            "bookName" -> runtimeContext?.getBook()?.name?.takeIf { !it.isNullOrEmpty() }?.let { return it }
            "bookAuthor" -> runtimeContext?.getBook()?.author?.takeIf { !it.isNullOrEmpty() }?.let { return it }
            "bookUrl" -> runtimeContext?.getBook()?.bookUrl?.takeIf { it.isNotEmpty() }?.let { return it }
            "chapterName" -> runtimeContext?.getChapter()?.name?.takeIf { it.isNotEmpty() }?.let { return it }
            "chapterUrl" -> runtimeContext?.getChapter()?.chapterUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        runtimeContext?.getChapter()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        runtimeContext?.getBook()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        runtimeContext?.getVariableAny(key)?.let { value ->
            if (value.toString().isNotEmpty()) return value
        }
        runtimeContext?.getSourceVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }

        val contextValue = jsContext?.getVariable(key)
        if (contextValue != null) return contextValue
        return jsVariables[key] ?: ""
    }
    
    /**
     * 设置 JavaScript 变量
     * 
     * 用于在 JavaScript 代码中存储临时变量，并确保其在当前分析任务中持久化
     * 
     * @param key 键名
     * @param value 值
     */
    fun put(key: String, value: Any?) {
        jsVariables[key] = value
        val stringValue = value?.toString().orEmpty()
        val runtimeContext = jsContext?.runtimeContext
        runtimeContext?.getChapter()?.putVariable(key, stringValue)
            ?: runtimeContext?.getBook()?.putVariable(key, stringValue)
        runtimeContext?.putVariableAny(key, value)
        runtimeContext?.putSourceVariable(key, stringValue)
        jsContext?.setVariable(key, value)
        Log.d(TAG, "Set JavaScript variable: $key = $value (persistent across runtime/source context)")
    }

    @Suppress("unused")
    fun reGetBook() {
        jsContext?.runtimeContext?.reGetBook()
    }

    @Suppress("unused")
    fun refreshTocUrl() {
        jsContext?.runtimeContext?.refreshTocUrl()
    }
    
    /**
     * 获取当前源的唯一标识符
     * 
     * 用于 Cookie 管理等需要源标识的场景
     * 
     * @return 源标识符
     */
    fun getSourceKey(): String {
        return jsContext?.source?.bookSourceUrl ?: "unknown_source"
    }

    @Suppress("unused")
    fun getCookie(tag: String): String {
        return getCookie(tag, null)
    }

    @Suppress("unused")
    fun getCookie(tag: String, key: String?): String {
        val cookieHeader = cookieJar?.getCookieHeader(tag).orEmpty()
        if (key.isNullOrBlank()) return cookieHeader
        return cookieHeader.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            .orEmpty()
    }

    @Suppress("unused")
    fun putCookie(tag: String, cookie: String?) {
        val activeCookieJar = cookieJar ?: return
        LegadoCookieAPI(activeCookieJar).replaceCookie(tag, cookie.orEmpty())
    }

    @Suppress("unused")
    fun removeCookie(tag: String) {
        val activeCookieJar = cookieJar ?: return
        LegadoCookieAPI(activeCookieJar).removeCookie(tag)
    }
    
    /**
     * Get host part of a URL
     */
    fun getHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val uri = java.net.URI(url)
            uri.host ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Get host of current source
     */
    fun bhost(): String {
        return getHost(getSourceKey())
    }

    @Suppress("unused")
    fun toURL(url: String): JsURL {
        return toURL(url, null)
    }

    @Suppress("unused")
    fun toURL(url: String, baseUrl: String?): JsURL {
        return JsURL(
            url,
            baseUrl ?: jsContext?.baseUrl,
        )
    }
    
    /**
     * 输出调试日志
     * 
     * @param msg 日志消息
     * @return 原始消息（支持链式调用）
     */
    fun log(msg: Any?): Any? {
        Log.d(TAG, "[JS Log] ${msg.toString()}")
        return msg
    }
    
    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }
    
    /**
     * URL 编码
     * 
     * @param str 需要编码的字符串
     * @return URL 编码后的字符串
     */
    fun encodeURI(str: String): String {
        return try {
            java.net.URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * URL 编码（指定编码）
     */
    fun encodeURI(str: String, enc: String): String {
        return try {
            java.net.URLEncoder.encode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * 生成 UUID
     */
    fun randomUUID(): String {
        return java.util.UUID.randomUUID().toString()
    }
    
    /**
     * 字符串转字节数组
     */
    fun strToBytes(str: String): ByteArray {
        return str.toByteArray(Charsets.UTF_8)
    }
    
    fun strToBytes(str: String, charset: String): ByteArray {
        return str.toByteArray(Charset.forName(charset))
    }
    
    /**
     * 字节数组转字符串
     */
    fun bytesToStr(bytes: ByteArray): String {
        return String(bytes, Charsets.UTF_8)
    }
    
    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return String(bytes, Charset.forName(charset))
    }
    
    /**
     * MD5 编码（16位）
     */
    fun md5Encode16(str: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(str.toByteArray())
            // 取中间16位
            digest.slice(4..11).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * MD5 编码（32位）
     */
    fun md5Encode(str: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(str.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("unused")
    fun digestHex(
        data: String,
        algorithm: String,
    ): String {
        return runCatching {
            MessageDigest.getInstance(algorithm)
                .digest(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun digestBase64Str(
        data: String,
        algorithm: String,
    ): String {
        return runCatching {
            java.util.Base64.getEncoder().encodeToString(
                MessageDigest.getInstance(algorithm).digest(data.toByteArray(Charsets.UTF_8)),
            )
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun HMacHex(
        data: String,
        algorithm: String,
        key: String,
    ): String {
        return runCatching {
            val mac = javax.crypto.Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm))
            mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    @Suppress("unused")
    fun HMacBase64(
        data: String,
        algorithm: String,
        key: String,
    ): String {
        return runCatching {
            val mac = javax.crypto.Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm))
            java.util.Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
        }.getOrDefault("")
    }
    
    /**
     * 获取 WebView User-Agent
     */
    fun getWebViewUA(): String {
        return try {
            android.webkit.WebSettings.getDefaultUserAgent(context)
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
        }
    }
    
    /**
     * HTML 格式化，保留图片
     */
    fun htmlFormat(str: String): String {
        // 简单实现：移除HTML标签但保留img
        val imgPattern = "<img[^>]*src=\"([^\"]*)\"[^>]*>".toRegex()
        val images = imgPattern.findAll(str).map { it.value }.toList()
        val text = str.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (images.isEmpty()) text else "$text\n${images.joinToString("\n")}"
    }

    fun encodingDetect(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return org.skepsun.kototoro.core.util.EncodingDetect.getEncode(bytes)
    }

    fun encodingDetect(str: String): String? {
        if (str.length > 16 * 1024) {
            return encodingDetect(str.take(16 * 1024).toByteArray())
        }
        return encodingDetect(str.toByteArray())
    }

    /**
     * 访问网络并返回 StrResponse（与 legado 兼容）。
     */
    fun connect(url: String): StrResponse {
        return connect(url, null, null)
    }

    fun connect(url: String, headers: String?): StrResponse {
        return connect(url, headers, null)
    }

    fun connect(url: String, headers: String?, callTimeout: Long?): StrResponse {
        return try {
            val defaultHeaders = parseHeaderMap(headers)
                ?.mapValues { (_, value) -> value.toString() }
                .orEmpty()
            executeRequest(
                url = url,
                options = null,
                defaultHeaders = defaultHeaders,
                callTimeoutMs = callTimeout,
            )
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: $url - ${e.message}")
            StrResponse(url, "")
        }
    }

    /**
     * 使用 WebView 加载页面并返回内容。
     */
    @Suppress("unused")
    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? {
        try {
            return runBlocking {
                loadInWebView(
                    html = html,
                    url = url,
                    js = js,
                    delayTime = 3000L,
                    extractor = null,
                    headers = buildSourceWebViewHeaders(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "webView failed: $url - ${e.message}")
            return null
        }
    }

    @Suppress("unused")
    fun webView(html: String?, url: String?, js: String?): String? {
        return webView(html, url, js, false)
    }

    @Suppress("unused")
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? {
        return webViewGetSource(html, url, js, sourceRegex, false, 0)
    }

    @Suppress("unused")
    fun webViewGetSource(
        html: String?,
        url: String?,
        js: String?,
        sourceRegex: String,
        cacheFirst: Boolean,
    ): String? {
        return webViewGetSource(html, url, js, sourceRegex, cacheFirst, 0)
    }

    @Suppress("unused")
    fun webViewGetSource(
        html: String?, url: String?, js: String?, sourceRegex: String,
        cacheFirst: Boolean, delayTime: Long
    ): String? {
        return try {
            if (!html.isNullOrBlank()) {
                val content = runBlocking {
                    loadInWebView(
                        html = html,
                        url = url,
                        js = js,
                        delayTime = delayTime,
                        extractor = null,
                        headers = buildSourceWebViewHeaders(),
                    )
                }
                if (content.isNullOrBlank()) return null
                if (sourceRegex.isNotBlank()) {
                    val pattern = runCatching { Regex(sourceRegex) }.getOrNull()
                    if (pattern != null) {
                        return pattern.find(content)?.value
                    }
                }
                return content
            }
            val result = runBlocking {
                httpClient.getWithWebView(
                    url = url ?: "",
                    headers = buildSourceWebViewHeaders(),
                    delayMs = delayTime,
                    webJs = js,
                    sourceRegex = sourceRegex,
                )
            }
            result.body.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "webViewGetSource failed: $url - ${e.message}")
            null
        }
    }

    @Suppress("unused")
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String,
    ): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false, 0)
    }

    @Suppress("unused")
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String,
        cacheFirst: Boolean,
    ): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, 0)
    }

    @Suppress("unused")
    fun webViewGetOverrideUrl(
        html: String?, url: String?, js: String?, overrideUrlRegex: String,
        cacheFirst: Boolean, delayTime: Long
    ): String? {
        if (overrideUrlRegex.isBlank()) return null
        return try {
            if (!html.isNullOrBlank()) {
                val content = runBlocking {
                    loadInWebView(
                        html = html,
                        url = url,
                        js = js,
                        delayTime = delayTime,
                        extractor = null,
                        headers = buildSourceWebViewHeaders(),
                    )
                }
                if (content.isNullOrBlank()) return null
                return runCatching {
                    Regex(overrideUrlRegex).find(content)?.value
                }.getOrNull()
            }
            val result = runBlocking {
                httpClient.getWebViewOverrideUrl(
                    url = url ?: "",
                    headers = buildSourceWebViewHeaders(),
                    delayMs = delayTime,
                    webJs = js,
                    overrideUrlRegex = overrideUrlRegex,
                )
            }
            result?.body?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "webViewGetOverrideUrl failed: $url - ${e.message}")
            null
        }
    }

    /**
     * 并发访问网络，返回 StrResponse 数组（与 legado 兼容）。
     */
    @Suppress("unused")
    fun ajaxAll(urlList: Array<String>): Array<StrResponse> {
        return ajaxAll(urlList, false)
    }

    @Suppress("unused")
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        return runBlocking {
            coroutineScope {
                urlList.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            executeRequest(url, null, skipRateLimit = skipRateLimit)
                        } catch (_: Exception) {
                            StrResponse(url, "")
                        }
                    }
                }.awaitAll().toTypedArray()
            }
        }
    }

    @Suppress("unused")
    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> {
        return ajaxTestAll(urlList, timeout, false)
    }

    @Suppress("unused")
    fun ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean): Array<StrResponse> {
        return runBlocking {
            coroutineScope {
                urlList.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            executeRequest(url, null, callTimeoutMs = timeout.toLong(), skipRateLimit = skipRateLimit)
                        } catch (_: Exception) {
                            StrResponse(url, "")
                        }
                    }
                }.awaitAll().toTypedArray()
            }
        }
    }
    
    /**
     * 打开内置浏览器并等待验证结果返回。
     */
    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, true)
    }

    @Suppress("unused")
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        return startBrowserAwait(url, title, refetchAfterSuccess, null)
    }

    @Suppress("unused")
    fun startBrowserAwait(
        url: String,
        title: String,
        refetchAfterSuccess: Boolean,
        html: String?,
    ): StrResponse {
        Log.i(TAG, "startBrowserAwait called: url=$url, title=$title")
        
        try {
            toast("正在启动浏览器进行验证...")
            val result = browserLauncher.launchAndWait(
                url = url,
                title = title,
                source = resolveParserContentSource(),
                refetchAfterSuccess = refetchAfterSuccess,
                html = html,
            )
            if (result?.html?.isNotBlank() == true) {
                toast("浏览器验证完成，Cookie 已同步")
            }
            Log.i(TAG, "Browser verification completed for: $url")
            return StrResponse(result?.url ?: url, result?.html.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser", e)
            toast("启动浏览器失败: ${e.message}")
            return StrResponse(url, "")
        }
    }

    /**
     * legado 常用：打开内置浏览器（不等待结果）。
     *
     * 三方脚本里常写 `java.startBrowser(url, title)` 用于跳转介绍页/发布页等。
     */
    @Suppress("unused")
    fun startBrowser(url: String, title: String = "") {
        startBrowser(url, title, null)
    }

    @Suppress("unused")
    fun startBrowser(url: String, title: String, html: String?) {
        try {
            val intent = org.skepsun.kototoro.core.nav.AppRouter.browserIntent(
                context = context,
                url = url,
                source = resolveParserContentSource(),
                title = title,
            ).putExtra(org.skepsun.kototoro.core.nav.AppRouter.KEY_BROWSER_HTML, html)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startBrowser failed: $url - ${e.message}", e)
        }
    }

    @Suppress("unused")
    fun startBrowser(url: String) {
        startBrowser(url, "")
    }

    @Suppress("unused")
    fun getVerificationCode(imageUrl: String): String {
        return startBrowserAwait(imageUrl, "", false).body()
    }

    @Suppress("unused")
    fun importScript(path: String): String {
        val content = when {
            path.startsWith("http", ignoreCase = true) -> cacheFile(path)
            else -> readTxtFile(path)
        }
        require(content.isNotBlank()) { "$path 内容获取失败或者为空" }
        return content
    }

    @Suppress("unused")
    fun cacheFile(urlStr: String): String {
        return cacheFile(urlStr, 0)
    }

    @Suppress("unused")
    fun cacheFile(urlStr: String, saveTime: Int): String {
        val cacheRoot = getScriptCacheDir().apply { mkdirs() }
        val key = md5Encode16(urlStr)
        val targetFile = File(cacheRoot, "$key.txt")
        val now = System.currentTimeMillis()
        if (targetFile.exists()) {
            val expired = saveTime > 0 && now - targetFile.lastModified() > saveTime * 1000L
            if (!expired) {
                return targetFile.readText(detectCharset(targetFile.readBytes()))
            }
        }
        val bytes = executeRequestBytes(urlStr, null)
        if (bytes.isEmpty()) return ""
        targetFile.writeBytes(bytes)
        if (saveTime > 0) {
            targetFile.setLastModified(now)
        }
        return targetFile.readText(detectCharset(targetFile.readBytes()))
    }

    @Suppress("unused")
    fun getFile(path: String): File {
        val root = getSafeFileRoot()
        val candidate = if (path.startsWith(File.separator)) {
            File(root, path.removePrefix(File.separator))
        } else {
            File(root, path)
        }
        val canonicalRoot = root.canonicalFile
        val canonicalFile = candidate.canonicalFile
        require(canonicalFile.path.startsWith(canonicalRoot.path)) { "非法路径" }
        return canonicalFile
    }

    @Suppress("unused")
    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        return if (file.exists()) file.readBytes() else null
    }

    @Suppress("unused")
    fun readTxtFile(path: String): String {
        val bytes = readFile(path) ?: return ""
        return String(bytes, detectCharset(bytes))
    }

    @Suppress("unused")
    fun readTxtFile(path: String, charsetName: String): String {
        val bytes = readFile(path) ?: return ""
        return String(bytes, runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8))
    }

    @Suppress("unused")
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return file.exists() && file.deleteRecursively()
    }

    @Suppress("unused")
    fun downloadFile(url: String): String {
        val bytes = executeRequestBytes(url, null)
        if (bytes.isEmpty()) return ""
        val baseUrl = jsContext?.baseUrl ?: jsContext?.source?.bookSourceUrl.orEmpty()
        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = jsContext?.source?.enabledCookieJar != false,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0L,
            defaultHeaders = emptyMap(),
            jsEvaluator = jsEvaluator,
        ).build(url)
        val extension = plan.type?.takeIf { it.isNotBlank() } ?: guessExtension(plan.url)
        val file = File(getScriptCacheDir().apply { mkdirs() }, "${md5Encode16(url)}.$extension")
        file.writeBytes(bytes)
        return file.absolutePath.removePrefix(getSafeFileRoot().absolutePath)
    }

    @Suppress("unused")
    fun downloadFile(content: String, url: String): String {
        val bytes = hexDecodeToByteArray(content) ?: return ""
        if (bytes.isEmpty()) return ""
        val baseUrl = jsContext?.baseUrl ?: jsContext?.source?.bookSourceUrl.orEmpty()
        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = jsContext?.source?.enabledCookieJar != false,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0L,
            defaultHeaders = emptyMap(),
            jsEvaluator = jsEvaluator,
        ).build(url)
        val extension = plan.type?.takeIf { it.isNotBlank() } ?: return ""
        val file = File(getScriptCacheDir().apply { mkdirs() }, "${md5Encode16(url)}.$extension")
        file.writeBytes(bytes)
        return file.absolutePath.removePrefix(getSafeFileRoot().absolutePath)
    }

    @Suppress("unused")
    fun get(urlStr: String, headers: Map<String, String>): Connection.Response {
        return get(urlStr, headers, null)
    }

    @Suppress("unused")
    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return executeJsoupMethodRequest(
            url = urlStr,
            method = Connection.Method.GET,
            headers = headers,
            timeout = timeout,
            body = null,
        )
    }

    @Suppress("unused")
    fun head(urlStr: String, headers: Map<String, String>): Connection.Response {
        return head(urlStr, headers, null)
    }

    @Suppress("unused")
    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return executeJsoupMethodRequest(
            url = urlStr,
            method = Connection.Method.HEAD,
            headers = headers,
            timeout = timeout,
            body = null,
        )
    }

    @Suppress("unused")
    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response {
        return post(urlStr, body, headers, null)
    }

    @Suppress("unused")
    fun post(
        urlStr: String,
        body: String,
        headers: Map<String, String>,
        timeout: Int?,
    ): Connection.Response {
        return executeJsoupMethodRequest(
            url = urlStr,
            method = Connection.Method.POST,
            headers = headers,
            timeout = timeout,
            body = body,
        )
    }

    @Suppress("unused")
    fun openUrl(url: String) {
        openUrl(url, null)
    }

    @Suppress("unused")
    fun openUrl(url: String, mimeType: String?) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        buildLegadoImportIntent(url)?.let { importIntent ->
            runCatching { context.startActivity(importIntent) }
                .onFailure { Log.e(TAG, "openUrl import failed: $url - ${it.message}", it) }
            return
        }
        val source = requireNotNull(jsContext?.source) { "openUrl source cannot be null" }
        val activity = requireNotNull(context as? AppCompatActivity) { "openUrl activity context required" }
        val sourceOrigin = resolveParserContentSource()?.name ?: source.bookSourceUrl
        val intent = OpenUrlConfirmActivity.newIntent(
            activity = activity,
            url = url,
            mimeType = mimeType,
            sourceOrigin = sourceOrigin,
            sourceName = source.bookSourceName,
            sourceType = LEGADO_SOURCE_TYPE_BOOK,
        )
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Log.e(TAG, "openUrl failed: $url - ${it.message}", it)
        }
    }

    private fun buildLegadoImportIntent(url: String): Intent? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "legado" && scheme != "yuedu") {
            return null
        }
        val importUrl = uri.getQueryParameter("src").orEmpty().ifBlank { return null }
        return UnifiedSourcesActivity.newIntent(
            context = context,
            initialRepositoryKind = UnifiedSourceKind.LEGADO,
            initialRepositoryUrl = importUrl,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("unused")
    fun unzipFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    @Suppress("unused")
    fun un7zFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    @Suppress("unused")
    fun unrarFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    @Suppress("unused")
    fun unArchiveFile(zipPath: String): String {
        if (zipPath.isBlank()) return ""
        val archiveFile = getFile(zipPath)
        if (!archiveFile.exists() || !archiveFile.isFile) return ""
        val targetName = md5Encode16(archiveFile.name)
        val archiveTempRoot = File(getSafeFileRoot(), ARCHIVE_TEMP_FOLDER_NAME).apply { mkdirs() }
        val outputDir = File(archiveTempRoot, targetName).apply {
            deleteRecursively()
            mkdirs()
        }
        return runCatching {
            archiveFile.inputStream().buffered().use { input ->
                LibArchiveUtils.unArchive(input, outputDir)
            }
            "$ARCHIVE_TEMP_FOLDER_NAME${File.separator}$targetName"
        }.getOrElse {
            Log.e(TAG, "unArchiveFile failed: $zipPath - ${it.message}", it)
            outputDir.deleteRecursively()
            ""
        }
    }

    @Suppress("unused")
    fun getTxtInFolder(path: String): String {
        if (path.isBlank()) return ""
        val folder = getFile(path)
        if (!folder.exists() || !folder.isDirectory) return ""
        val contents = buildString {
            folder.listFiles()
                ?.asSequence()
                ?.filter { it.isFile() }
                ?.sortedBy { it.getName() }
                ?.forEach { file ->
                    val bytes = file.readBytes()
                    val charset = if (bytes.all { it >= 0 }) {
                        Charsets.UTF_8
                    } else {
                        val charsetName = org.skepsun.kototoro.core.util.EncodingDetect.getEncode(bytes)
                        runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
                    }
                    append(String(bytes, charset))
                    append('\n')
                }
        }
        folder.deleteRecursively()
        return contents.removeSuffix("\n")
    }

    @Suppress("unused")
    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, detectCharset(byteArray))
    }

    @Suppress("unused")
    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8))
    }

    @Suppress("unused")
    fun get7zStringContent(url: String, path: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        return String(byteArray, detectCharset(byteArray))
    }

    @Suppress("unused")
    fun get7zStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        return String(byteArray, runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8))
    }

    @Suppress("unused")
    fun getRarStringContent(url: String, path: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        return String(byteArray, detectCharset(byteArray))
    }

    @Suppress("unused")
    fun getRarStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        return String(byteArray, runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8))
    }

    @Deprecated(
        "Deprecated",
        ReplaceWith("queryTTF(data)")
    )
    @Suppress("unused")
    fun queryBase64TTF(data: String?): QueryTTF? {
        log(
            "queryBase64TTF(String)方法已过时,并将在未来删除；请无脑使用queryTTF(Any)替代，新方法支持传入 url、本地文件、base64、ByteArray 自动判断&自动缓存，特殊情况需禁用缓存请传入第二可选参数false:Boolean",
        )
        return queryTTF(data)
    }

    @Suppress("unused")
    fun queryTTF(data: Any?): QueryTTF? {
        return queryTTF(data, true)
    }

    @Suppress("unused")
    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? {
        return try {
            var cacheKey: String? = null
            val queryTtf = when (data) {
                is String -> {
                    if (useCache) {
                        cacheKey = sha256Hex(data.toByteArray())
                        getCachedQueryTtf(cacheKey)?.let { return it }
                    }
                    val fontBytes = when {
                        data.startsWith("http://", ignoreCase = true) ||
                            data.startsWith("https://", ignoreCase = true) -> {
                            executeRequestBytes(data, null)
                        }

                        else -> {
                            val localFile = runCatching { getFile(data) }.getOrNull()
                            when {
                                localFile?.exists() == true && localFile.isFile -> localFile.readBytes()
                                else -> base64DecodeToByteArray(data)
                            }
                        }
                    } ?: return null
                    QueryTTF(fontBytes)
                }

                is ByteArray -> {
                    if (useCache) {
                        cacheKey = sha256Hex(data)
                        getCachedQueryTtf(cacheKey)?.let { return it }
                    }
                    QueryTTF(data)
                }

                else -> return null
            }
            cacheKey?.let { putCachedQueryTtf(it, queryTtf) }
            queryTtf
        } catch (e: Exception) {
            Log.e(TAG, "[queryTTF] 获取字体处理类出错", e)
            throw e
        }
    }

    @Suppress("unused")
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?,
    ): String {
        return replaceFont(text, errorQueryTTF, correctQueryTTF, false)
    }

    @Suppress("unused")
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?,
        filter: Boolean,
    ): String {
        if (errorQueryTTF == null || correctQueryTTF == null) return text
        val contentArray = splitToCodePointStrings(text)
        contentArray.forEachIndexed { index, value ->
            val oldCode = value.codePointAt(0)
            if (errorQueryTTF.isBlankUnicode(oldCode)) {
                return@forEachIndexed
            }
            var glyf = errorQueryTTF.getGlyfByUnicode(oldCode)
            if (errorQueryTTF.getGlyfIdByUnicode(oldCode) == 0) {
                glyf = null
            }
            if (filter && glyf == null) {
                contentArray[index] = ""
                return@forEachIndexed
            }
            val code = correctQueryTTF.getUnicodeByGlyf(glyf)
            if (code != 0) {
                contentArray[index] = String(Character.toChars(code))
            }
        }
        return contentArray.joinToString("")
    }

    @Suppress("unused")
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = loadRemoteOrHexBytes(url) ?: return null
        return runCatching {
            val output = java.io.ByteArrayOutputStream()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    if (entry.name == path) {
                        output.reset()
                        zipInput.copyTo(output)
                        return@runCatching output.toByteArray()
                    }
                }
            }
            null
        }.getOrElse {
            Log.e(TAG, "getZipByteArrayContent failed: ${it.message}", it)
            null
        }
    }

    @Suppress("unused")
    fun get7zByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = loadRemoteOrHexBytes(url) ?: return null
        return runCatching {
            ByteArrayInputStream(bytes).use {
                LibArchiveUtils.getByteArrayContent(it, path)
            }
        }.getOrElse {
            Log.e(TAG, "get7zByteArrayContent failed: ${it.message}", it)
            null
        }
    }

    @Suppress("unused")
    fun getRarByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = loadRemoteOrHexBytes(url) ?: return null
        return runCatching {
            ByteArrayInputStream(bytes).use {
                LibArchiveUtils.getByteArrayContent(it, path)
            }
        }.getOrElse {
            Log.e(TAG, "getRarByteArrayContent failed: ${it.message}", it)
            null
        }
    }

    @Suppress("unused")
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val match = TITLE_NUM_PATTERN.find(s) ?: return s
        val intStr = stringToInt(match.groupValues[2])
        return "${match.groupValues[1]}${intStr}${match.groupValues[3]}"
    }

    @Suppress("unused")
    fun getThemeMode(): String {
        return when (AppSettings(context).theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> "1"
            AppCompatDelegate.MODE_NIGHT_YES -> "2"
            else -> "0"
        }
    }

    @Suppress("unused")
    fun getThemeConfig(): String {
        return toJsonObject(getThemeConfigMap()).toString()
    }

    @Suppress("unused")
    fun getThemeConfigMap(): Map<String, Any?> {
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

    @Suppress("unused")
    fun getReadBookConfig(): String {
        return toJsonObject(getReadBookConfigMap()).toString()
    }

    @Suppress("unused")
    fun getReadBookConfigMap(): Map<String, Any?> {
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

    class JsURL(
        url: String,
        baseUrl: String? = null,
    ) {
        val href: String
        val host: String
        val origin: String
        val pathname: String
        val search: String
        val searchParams: Map<String, String>?

        init {
            val resolved = if (!baseUrl.isNullOrBlank()) {
                URL(URL(baseUrl), url)
            } else {
                URL(url)
            }
            href = resolved.toString()
            host = resolved.host.orEmpty()
            origin = buildString {
                append(resolved.protocol)
                append("://")
                append(host)
                if (resolved.port > 0) {
                    append(':')
                    append(resolved.port)
                }
            }
            pathname = resolved.path.orEmpty()
            search = resolved.query?.let { "?$it" }.orEmpty()
            searchParams = resolved.query?.takeIf { it.isNotBlank() }?.split("&")?.associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts.firstOrNull().orEmpty()
                val value = parts.getOrNull(1)?.let {
                    runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it)
                }.orEmpty()
                key to value
            }
        }

        override fun toString(): String = href
    }

    private fun parseHeaderMap(headers: String?): Map<String, Any>? {
        if (headers.isNullOrBlank()) return null
        return runCatching {
            val obj = org.json.JSONObject(headers)
            buildMap<String, Any> {
                obj.keys().forEach { key ->
                    put(key, obj.opt(key) ?: return@forEach)
                }
            }
        }.getOrNull()
    }

    private fun executeRequest(
        url: String,
        options: Map<String, Any>?,
        defaultHeaders: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
        skipRateLimit: Boolean = false,
    ): StrResponse {
        val effectiveUrl = mergeInlineOptions(url, options)
        val baseUrl = jsContext?.baseUrl ?: jsContext?.source?.bookSourceUrl.orEmpty()
        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = jsContext?.source?.enabledCookieJar != false,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0L,
            defaultHeaders = defaultHeaders,
            jsEvaluator = jsEvaluator,
        ).build(effectiveUrl)

        val response = runBlocking { executePlan(plan, callTimeoutMs, skipRateLimit) }
        return response.toStrResponse()
    }

    private fun mergeInlineOptions(url: String, options: Map<String, Any>?): String {
        val trimmed = url.trim()
        if (options.isNullOrEmpty()) return trimmed
        val (_, inlineOptions) = parseInlineOptions(trimmed)
        val urlPart = trimmed.substringBefore(",{").substringBefore(", {").trim()
        val merged = linkedMapOf<String, Any>()
        inlineOptions?.forEach { (key, value) -> merged[key] = value }
        options.forEach { (key, value) -> merged[key] = value }
        return "$urlPart,${org.json.JSONObject(merged).toString()}"
    }

    private suspend fun loadInWebView(
        html: String?,
        url: String?,
        js: String?,
        delayTime: Long,
        extractor: String?,
        headers: Map<String, String>,
    ): String? {
        if (!html.isNullOrBlank()) {
            val baseUrl = url?.takeIf { it.isNotBlank() } ?: "about:blank"
            return httpClient.loadHtmlWithWebView(
                html = html,
                baseUrl = baseUrl,
                delayMs = delayTime,
                webJs = extractor ?: js,
                userAgent = headers.entries.firstOrNull {
                    it.key.equals("User-Agent", ignoreCase = true)
                }?.value,
            )
        }
        return httpClient.getWithWebView(
            url = url ?: "",
            headers = headers,
            delayMs = delayTime,
            webJs = extractor ?: js,
        ).body
    }

    private fun buildSourceWebViewHeaders(): Map<String, String> {
        val source = jsContext?.source ?: return emptyMap()
        val effectiveCookieJar = cookieJar ?: org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar(
            httpClient.getCookieJar(),
        )
        return RhinoJavaScriptEngine.SourceWrapper(
            source = source,
            context = context,
            cookieJar = effectiveCookieJar,
            jsExecutor = { script, scriptContext ->
                RhinoJavaScriptEngine(
                    httpClient = httpClient,
                    cookieManager = cookieManager,
                    cookieJar = effectiveCookieJar,
                    androidContext = context,
                ).execute(script, scriptContext)
            },
            contextFactory = { extraBindings ->
                JavaScriptContext(
                    baseUrl = jsContext?.baseUrl,
                    book = jsContext?.book,
                    chapter = jsContext?.chapter,
                    source = jsContext?.source,
                    sourceName = jsContext?.sourceName,
                    runtimeContext = jsContext?.runtimeContext,
                    key = jsContext?.key,
                    page = jsContext?.page,
                    result = jsContext?.result,
                ).also { nestedContext ->
                    jsContext?.getAllVariables()?.forEach { (name, value) ->
                        nestedContext.setVariable(name, value)
                    }
                    extraBindings.forEach { (name, value) ->
                        nestedContext.setVariable(name, value)
                    }
                }
            },
        ).getHeaderMap(true)
    }

    private fun evaluateInlineJs(script: String, result: Any?): Any? {
        val activeContext = jsContext ?: return result
        val rhino = RhinoContext.enter()
        val previousResult = activeContext.result
        return try {
            rhino.optimizationLevel = -1
            rhino.languageVersion = 200
            val scope = rhino.initStandardObjects()
            activeContext.result = result
            activeContext.getAllVariables().forEach { (name, value) ->
                ScriptableObject.putProperty(scope, name, toJsValue(rhino, scope, value))
            }
            ScriptableObject.putProperty(scope, "java", RhinoContext.javaToJS(this, scope))
            val contentToSet = result ?: activeContext.getVariable("result")
            if (contentToSet != null) {
                setContent(contentToSet)
            }
            val evalResult = rhino.evaluateString(scope, script, "legado-inline", 1, null)
            syncScopeBackToContext(scope, activeContext)
            val hostResult = fromJsValue(evalResult)
            if (hostResult == null || hostResult.toString() == "undefined") {
                activeContext.result ?: result
            } else {
                hostResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateInlineJs failed: ${e.message}")
            result
        } finally {
            if (activeContext.result == null && previousResult != null) {
                activeContext.result = previousResult
            }
            RhinoContext.exit()
        }
    }

    private fun syncScopeBackToContext(
        currentScope: Scriptable,
        context: JavaScriptContext,
    ) {
        val protectedKeys = setOf(
            "java",
            "cookie",
            "cache",
            "source",
            "book",
            "chapter",
            "Packages",
            "getClass",
            "JavaAdapter",
            "JavaImporter",
            "Continuation",
            "importClass",
            "importPackage",
        )
        currentScope.ids.forEach { id ->
            val key = when (id) {
                is String -> id
                is Number -> return@forEach
                else -> id?.toString() ?: return@forEach
            }
            if (key in protectedKeys) return@forEach
            val rawValue = ScriptableObject.getProperty(currentScope, key)
            if (rawValue == Scriptable.NOT_FOUND) return@forEach
            val value = fromJsValue(rawValue)
            when (key) {
                "result" -> context.result = value
                "src" -> context.src = value
                else -> context.setVariable(key, value)
            }
        }
    }

    private fun fromJsValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is NativeJavaObject -> value.unwrap()
            else -> runCatching { RhinoContext.jsToJava(value, Any::class.java) }.getOrDefault(value)
        }
    }

    private fun toJsValue(
        context: RhinoContext,
        scope: Scriptable,
        value: Any?,
    ): Any? {
        return when (value) {
            null -> null
            is List<*> -> {
                val array = context.newArray(scope, value.size)
                value.forEachIndexed { index, item ->
                    ScriptableObject.putProperty(array, index, toJsValue(context, scope, item))
                }
                array
            }

            is Array<*> -> {
                val array = context.newArray(scope, value.size)
                value.forEachIndexed { index, item ->
                    ScriptableObject.putProperty(array, index, toJsValue(context, scope, item))
                }
                array
            }

            is Map<*, *> -> {
                val obj = context.newObject(scope)
                value.forEach { (key, item) ->
                    val property = key?.toString() ?: return@forEach
                    ScriptableObject.putProperty(obj, property, toJsValue(context, scope, item))
                }
                obj
            }

            is String -> context.newObject(scope, "String", arrayOf(value))
            else -> RhinoContext.javaToJS(value, scope)
        }
    }

    private fun formatSourceScopedMessage(message: Any?): String {
        val tag = getTag()?.takeIf { it.isNotBlank() }
        return if (tag != null) {
            "$tag: ${message.toString()}"
        } else {
            message.toString()
        }
    }

    private fun executeJsoupMethodRequest(
        url: String,
        method: Connection.Method,
        headers: Map<String, String>,
        timeout: Int?,
        body: String?,
    ): Connection.Response {
        val requestHeaders = headers.toMutableMap().apply {
            if (jsContext?.source?.enabledCookieJar != false) {
                val cookieHeader = cookieJar?.getCookieHeader(url).orEmpty()
                if (cookieHeader.isNotBlank() && keys.none { it.equals("Cookie", ignoreCase = true) }) {
                    put("Cookie", cookieHeader)
                }
            }
        }
        val rateLimiter = ConcurrentRateLimiter(
            sourceKey = jsContext?.source?.bookSourceUrl.orEmpty(),
            concurrentRate = jsContext?.source?.concurrentRate,
        )
        return runCatching {
            runBlocking {
                rateLimiter.withLimit {
                    val connection = Jsoup.connect(url)
                        .timeout(timeout ?: 30000)
                        .ignoreContentType(true)
                        .followRedirects(false)
                        .headers(requestHeaders)
                        .method(method)
                    if (body != null) {
                        connection.requestBody(body)
                    }
                    connection.execute()
                }
            }
        }.getOrElse {
            Log.e(TAG, "$method failed: $url - ${it.message}", it)
            throw it
        }
    }

    private suspend fun executePlan(
        plan: org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan,
        callTimeoutMs: Long?,
        skipRateLimit: Boolean = false,
    ): Response {
        val mergedHeaders = plan.headers.toMutableMap()
        val effectiveCallTimeoutMs = callTimeoutMs ?: plan.callTimeoutMs
        suspend fun performRequest(): Response {
            return when (plan.method) {
                "POST" -> {
                    val body = plan.body ?: ""
                    val charsetSuffix = plan.charsetName
                        ?.takeIf { !it.equals("escape", ignoreCase = true) }
                        ?.let { "; charset=$it" }
                        ?: ""
                    val contentType = mergedHeaders.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value
                        ?: when {
                            plan.bodyIsForm || body.isBlank() -> "application/x-www-form-urlencoded$charsetSuffix"
                            body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "application/json$charsetSuffix"
                            else -> "text/plain$charsetSuffix"
                        }
                    httpClient.post(
                        url = plan.url,
                        body = body.toRequestBody(contentType.toMediaTypeOrNull()),
                        headers = mergedHeaders,
                        source = null,
                        proxy = plan.proxy,
                        dnsIp = plan.dnsIp,
                        enableCookieJar = plan.enableCookieJar,
                        readTimeoutMs = plan.readTimeoutMs,
                        callTimeoutMs = effectiveCallTimeoutMs,
                    )
                }

                "HEAD" -> httpClient.head(
                    url = plan.url,
                    headers = mergedHeaders,
                    callTimeoutMs = effectiveCallTimeoutMs,
                )

                else -> httpClient.get(
                    url = plan.url,
                    headers = mergedHeaders,
                    source = null,
                    proxy = plan.proxy,
                    dnsIp = plan.dnsIp,
                    enableCookieJar = plan.enableCookieJar,
                    readTimeoutMs = plan.readTimeoutMs,
                    callTimeoutMs = effectiveCallTimeoutMs,
                )
            }
        }
        if (skipRateLimit) {
            return performRequest()
        }
        val sourceKey = jsContext?.source?.bookSourceUrl.orEmpty()
        val concurrentRate = jsContext?.source?.concurrentRate
        return ConcurrentRateLimiter(sourceKey, concurrentRate).withLimit {
            performRequest()
        }
    }

    private fun Response.toStrResponse(readBody: Boolean = true): StrResponse {
        return use { response ->
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)
            val charset = response.body?.contentType()?.charset() ?: Charsets.UTF_8
            val bodyText = if (readBody) String(bodyBytes, charset) else ""
            val rawResponse = response.newBuilder()
                .body(bodyBytes.toResponseBody(response.body?.contentType()))
                .build()
            StrResponse(rawResponse, bodyText)
        }
    }

    private fun getSafeFileRoot(): File {
        fun pathToFile(path: String?): File? {
            return path?.takeIf { it.isNotBlank() }?.let(::File)
        }
        return pathToFile(runCatching { context.externalCacheDir?.absolutePath }.getOrNull())
            ?: pathToFile(runCatching { context.cacheDir?.absolutePath }.getOrNull())
            ?: File(System.getProperty("java.io.tmpdir"), "kototoro-legado-js").apply { mkdirs() }
    }

    private fun getScriptCacheDir(): File {
        return File(getSafeFileRoot(), "legado_js").apply { mkdirs() }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val charsetName = encodingDetect(bytes) ?: "UTF-8"
        return runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
    }

    private fun guessExtension(url: String): String {
        val cleaned = url.substringBefore('?').substringBefore('#')
        val ext = cleaned.substringAfterLast('.', "").trim()
        return ext.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
    }

    private fun executeRequestBytes(
        url: String,
        options: Map<String, Any>?,
        callTimeoutMs: Long? = null,
    ): ByteArray {
        val effectiveUrl = mergeInlineOptions(url, options)
        decodeDataUriBytes(effectiveUrl)?.let { return it }
        val baseUrl = jsContext?.baseUrl ?: jsContext?.source?.bookSourceUrl.orEmpty()
        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = jsContext?.source?.enabledCookieJar != false,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0L,
            defaultHeaders = emptyMap(),
            jsEvaluator = jsEvaluator,
        ).build(effectiveUrl)
        return runBlocking {
            executePlan(plan, callTimeoutMs).use { response ->
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    private fun loadRemoteOrHexBytes(value: String): ByteArray? {
        val trimmed = value.trim()
        decodeDataUriBytes(trimmed)?.let { return it }
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return runCatching { executeRequestBytes(trimmed, null) }.getOrNull()
        }
        return hexDecodeToByteArray(trimmed)
    }

    private fun decodeDataUriBytes(value: String): ByteArray? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return null
        val body = trimmed.substringAfter(',', "")
        return runCatching {
            if (trimmed.contains(";base64", ignoreCase = true)) {
                runCatching { java.util.Base64.getDecoder().decode(body) }
                    .recoverCatching { java.util.Base64.getUrlDecoder().decode(body) }
                    .getOrElse { android.util.Base64.decode(body, android.util.Base64.DEFAULT) }
            } else {
                URLDecoder.decode(body, Charsets.UTF_8.name()).toByteArray(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun splitToCodePointStrings(text: String): MutableList<String> {
        val result = ArrayList<String>(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            result += String(Character.toChars(codePoint))
            index += Character.charCount(codePoint)
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun getCachedQueryTtf(key: String): QueryTTF? {
        return synchronized(queryTtfCache) {
            queryTtfCache.get(key)
        }
    }

    private fun putCachedQueryTtf(key: String, queryTTF: QueryTTF) {
        synchronized(queryTtfCache) {
            queryTtfCache.put(key, queryTTF)
        }
    }

    private fun toJsonObject(map: Map<String, Any?>): org.json.JSONObject {
        val result = org.json.JSONObject()
        map.forEach { (key, value) ->
            result.put(key, toJsonValue(value))
        }
        return result
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            null -> org.json.JSONObject.NULL
            is Map<*, *> -> {
                val obj = org.json.JSONObject()
                value.forEach { (k, v) ->
                    if (k != null) obj.put(k.toString(), toJsonValue(v))
                }
                obj
            }

            is Iterable<*> -> {
                val array = org.json.JSONArray()
                value.forEach { array.put(toJsonValue(it)) }
                array
            }

            is Array<*> -> {
                val array = org.json.JSONArray()
                value.forEach { array.put(toJsonValue(it)) }
                array
            }

            is Enum<*> -> value.name
            else -> value
        }
    }

    private fun stringToInt(str: String?): Int {
        if (str != null) {
            val num = fullToHalf(str).replace("\\s+".toRegex(), "")
            return runCatching {
                num.toInt()
            }.getOrElse {
                chineseNumToInt(num)
            }
        }
        return -1
    }

    private fun fullToHalf(input: String): String {
        val chars = input.toCharArray()
        for (i in chars.indices) {
            when (chars[i].code) {
                12288 -> chars[i] = 32.toChar()
                in 65281..65374 -> chars[i] = (chars[i].code - 65248).toChar()
            }
        }
        return String(chars)
    }

    private fun chineseNumToInt(chNum: String): Int {
        var result = 0
        var tmp = 0
        var billion = 0
        val cn = chNum.toCharArray()
        return runCatching {
            if (cn.size > 1 && chNum.all { CHINESE_NUMBER_MAP[it] in 0..9 }) {
                for (i in cn.indices) {
                    cn[i] = (48 + (CHINESE_NUMBER_MAP[cn[i]] ?: 0)).toChar()
                }
                return@runCatching String(cn).toInt()
            }
            for (i in cn.indices) {
                val tmpNum = CHINESE_NUMBER_MAP[cn[i]] ?: return@runCatching -1
                when {
                    tmpNum == 100000000 -> {
                        result += tmp
                        result *= tmpNum
                        billion = billion * 100000000 + result
                        result = 0
                        tmp = 0
                    }

                    tmpNum == 10000 -> {
                        result += tmp
                        result *= tmpNum
                        tmp = 0
                    }

                    tmpNum >= 10 -> {
                        if (tmp == 0) tmp = 1
                        result += tmpNum * tmp
                        tmp = 0
                    }

                    else -> tmp = tmp * 10 + tmpNum
                }
            }
            result + tmp + billion
        }.getOrDefault(-1)
    }

}

/**
 * HTTP response wrapper (legado-with-MD3 兼容)。
 * JS 中通过 connect() / ajaxAll() 返回该类型对象，拥有 url 和 body 字段。
 */
class StrResponse {
    var raw: Response
        private set
    var body: String? = null
        private set
    var errorBody: okhttp3.ResponseBody? = null
        private set
    var callTime = 0

    constructor(rawResponse: Response, body: String?) {
        raw = rawResponse
        this.body = body
    }

    constructor(url: String, body: String?) {
        val request = runCatching { Request.Builder().url(url).build() }
            .getOrElse { Request.Builder().url("http://localhost/").build() }
        raw = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ByteArray(0).toResponseBody(null))
            .build()
        this.body = body
    }

    constructor(
        url: String,
        bodyText: String,
        code: Int,
        headers: Map<String, String>,
    ) {
        val request = runCatching { Request.Builder().url(url).build() }
            .getOrElse { Request.Builder().url("http://localhost/").build() }
        raw = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .headers(
                Headers.Builder().apply {
                    headers.forEach { (name, value) -> add(name, value) }
                }.build(),
            )
            .body(ByteArray(0).toResponseBody(null))
            .build()
        this.body = bodyText
    }

    constructor(rawResponse: Response, errorBody: okhttp3.ResponseBody?) {
        raw = rawResponse
        this.errorBody = errorBody
    }

    fun putCallTime(callTime: Int) {
        this.callTime = callTime
    }

    fun raw(): Response = raw

    fun callTime(): Int = callTime

    fun url(): String {
        return raw.networkResponse?.request?.url?.toString() ?: raw.request.url.toString()
    }

    val url: String
        get() = url()

    fun body(): String = body.orEmpty()

    fun code(): Int = raw.code

    fun message(): String = raw.message

    fun headers(): Headers = raw.headers

    fun header(name: String): String? = raw.header(name)

    fun isSuccessful(): Boolean = raw.isSuccessful

    fun errorBody(): okhttp3.ResponseBody? = errorBody

    override fun toString(): String {
        return raw.toString()
    }
}
