package org.skepsun.kototoro.core.parser.legado

import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.BitSet
import kotlin.math.max

/**
 * 将 Legado URL 规则字符串解析为标准请求结果。
 *
 * 行为尽量对齐 legado-with-MD3 的 AnalyzeUrl.analyzeUrl / analyzeQuery / analyzeFields。
 */
class LegadoRequestPlanBuilder(
    private val baseUrl: String,
    private val enabledCookieJarDefault: Boolean,
    private val useWebViewDefault: Boolean,
    private val webJsDefault: String?,
    private val webViewDelayDefault: Long,
    private val defaultHeaders: Map<String, String>,
    private val jsEvaluator: LegadoJsEvaluator,
    private val readTimeoutMs: Long? = null,
    private val callTimeoutMs: Long? = null,
) {

    fun build(ruleUrl: String): LegadoRequestPlan {
        val splitMatch = LegadoUrlRules.optionsSplitRegex.find(ruleUrl)
        if (splitMatch == null) {
            return buildRequestPlan(
                finalUrl = LegadoUrlRules.resolveUrl(baseUrl, ruleUrl.trim()),
                method = "GET",
                body = null,
                headers = defaultHeaders.withoutProxyHeader(),
                charsetName = null,
                useWebView = useWebViewDefault,
                enableCookieJar = enabledCookieJarDefault,
                webJs = webJsDefault,
                sourceRegex = null,
                overrideUrlRegex = null,
                webViewDelayTime = webViewDelayDefault,
                retry = 0,
                type = null,
                bodyJs = null,
                urlJs = null,
                dnsIp = null,
                serverId = null,
                proxy = defaultHeaders.findProxyHeader(),
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            )
        }

        val urlPart = ruleUrl.substring(0, splitMatch.range.first).trim()
        val optionsPart = ruleUrl.substring(splitMatch.range.last + 1).trim()
        val resolvedUrl = LegadoUrlRules.resolveUrl(baseUrl, urlPart)

        return try {
            val optionsJson = parseOptionsJson(optionsPart)
            val method = optionsJson.optString("method", "GET").uppercase()
            var body = optionsJson.opt("body")?.toLegadoBodyString()
            val bodyJs = optionsJson.optString("bodyJs").takeIf { it.isNotBlank() }
            val urlJs = optionsJson.optString("js").takeIf { it.isNotBlank() }
            val optionHeaders = parseHeaders(optionsJson.opt("headers"))
            val headers = LinkedHashMap<String, String>().apply {
                putAll(defaultHeaders.withoutProxyHeader())
                putAll(optionHeaders)
            }
            val charsetName = optionsJson.optString("charset").takeIf { it.isNotBlank() }

            var finalProcessedUrl = resolvedUrl
            if (!urlJs.isNullOrBlank()) {
                finalProcessedUrl = jsEvaluator.evaluate(urlJs, finalProcessedUrl)?.toString() ?: finalProcessedUrl
            }

            val useWebView = if (optionsJson.has("webView")) {
                optionsJson.opt("webView").toLegadoBoolean()
            } else {
                useWebViewDefault
            }
            val enableCookieJar = if (optionsJson.has("enabledCookieJar")) {
                optionsJson.opt("enabledCookieJar").toLegadoBoolean(default = enabledCookieJarDefault)
            } else {
                enabledCookieJarDefault
            }
            val webJs = optionsJson.optString("webJs").takeIf { it.isNotBlank() } ?: webJsDefault
            val sourceRegex = optionsJson.optString("sourceRegex").takeIf { it.isNotBlank() }
            val overrideUrlRegex = optionsJson.optString("overrideUrlRegex").takeIf { it.isNotBlank() }
            val webViewDelayTime = max(0L, optionsJson.optLong("webViewDelayTime", webViewDelayDefault))
            val retry = optionsJson.opt("retry").toLegadoInt()
            val type = optionsJson.optString("type").takeIf { it.isNotBlank() }
            val dnsIp = optionsJson.optString("dnsIp").takeIf { it.isNotBlank() }
            val proxy = optionsJson.optString("proxy").takeIf { it.isNotBlank() } ?: defaultHeaders.findProxyHeader()
            val serverId = optionsJson.opt("serverID").toLegadoLong()

            buildRequestPlan(
                finalUrl = finalProcessedUrl,
                method = method,
                body = body,
                headers = headers,
                charsetName = charsetName,
                useWebView = useWebView,
                enableCookieJar = enableCookieJar,
                webJs = webJs,
                sourceRegex = sourceRegex,
                overrideUrlRegex = overrideUrlRegex,
                webViewDelayTime = webViewDelayTime,
                retry = retry,
                type = type,
                bodyJs = bodyJs,
                urlJs = urlJs,
                dnsIp = dnsIp,
                serverId = serverId,
                proxy = proxy,
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            )
        } catch (e: Exception) {
            LegadoRuleLog.error("AnalyzeUrl", "Failed to parse URL options: $optionsPart", e)
            buildRequestPlan(
                finalUrl = resolvedUrl,
                method = "GET",
                body = null,
                headers = defaultHeaders.withoutProxyHeader(),
                charsetName = null,
                useWebView = useWebViewDefault,
                enableCookieJar = enabledCookieJarDefault,
                webJs = webJsDefault,
                sourceRegex = null,
                overrideUrlRegex = null,
                webViewDelayTime = webViewDelayDefault,
                retry = 0,
                type = null,
                bodyJs = null,
                urlJs = null,
                dnsIp = null,
                serverId = null,
                proxy = defaultHeaders.findProxyHeader(),
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            )
        }
    }

    private fun buildRequestPlan(
        finalUrl: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        charsetName: String?,
        useWebView: Boolean,
        enableCookieJar: Boolean,
        webJs: String?,
        sourceRegex: String?,
        overrideUrlRegex: String?,
        webViewDelayTime: Long,
        retry: Int,
        type: String?,
        bodyJs: String?,
        urlJs: String?,
        dnsIp: String?,
        serverId: Long?,
        proxy: String?,
        readTimeoutMs: Long?,
        callTimeoutMs: Long?,
    ): LegadoRequestPlan {
        var urlNoQuery = finalUrl
        var finalBody = body
        var bodyIsForm = false

        if (method == "GET") {
            val pos = finalUrl.indexOf('?')
            if (pos != -1) {
                val query = finalUrl.substring(pos + 1)
                urlNoQuery = finalUrl.substring(0, pos)
                val encodedQuery = encodeParams(query, charsetName, isQuery = true)
                return LegadoRequestPlan(
                    url = if (encodedQuery.isEmpty()) urlNoQuery else "$urlNoQuery?$encodedQuery",
                    method = method,
                    body = finalBody,
                    bodyIsForm = false,
                    headers = headers,
                    enableCookieJar = enableCookieJar,
                    charsetName = charsetName,
                    useWebView = useWebView,
                    webJs = webJs,
                    sourceRegex = sourceRegex,
                    overrideUrlRegex = overrideUrlRegex,
                    webViewDelayTime = webViewDelayTime,
                    retry = retry,
                    type = type,
                    bodyJs = bodyJs,
                    js = urlJs,
                    dnsIp = dnsIp,
                    serverId = serverId,
                    proxy = proxy,
                    readTimeoutMs = readTimeoutMs,
                    callTimeoutMs = callTimeoutMs,
                )
            }
        } else if (!finalBody.isNullOrBlank() && !looksLikeJson(finalBody) && !looksLikeXml(finalBody) &&
            headers.none { it.key.equals("Content-Type", ignoreCase = true) }
        ) {
            finalBody = encodeParams(finalBody, charsetName, isQuery = false)
            bodyIsForm = true
        }

        return LegadoRequestPlan(
            url = finalUrl,
            method = method,
            body = finalBody,
            bodyIsForm = bodyIsForm,
            headers = headers,
            enableCookieJar = enableCookieJar,
            charsetName = charsetName,
            useWebView = useWebView,
            webJs = webJs,
            sourceRegex = sourceRegex,
            overrideUrlRegex = overrideUrlRegex,
            webViewDelayTime = webViewDelayTime,
            retry = retry,
            type = type,
            bodyJs = bodyJs,
            js = urlJs,
            dnsIp = dnsIp,
            serverId = serverId,
            proxy = proxy,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        )
    }

    private fun parseOptionsJson(optionsPart: String): JSONObject {
        return LegadoLenientJsonParser.parseObject(optionsPart)
    }

    private fun parseHeaders(value: Any?): Map<String, String> {
        return when (value) {
            is JSONObject -> {
                buildMap {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val headerValue = value.opt(key)
                        if (headerValue != null && headerValue != JSONObject.NULL) {
                            put(key, headerValue.toString())
                        }
                    }
                }
            }

            is String -> runCatching { parseHeaders(JSONObject(value)) }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
    }

    private fun encodeParams(params: String, charsetName: String?, isQuery: Boolean): String {
        val checkEncoded = charsetName.isNullOrEmpty()
        val charset = when {
            charsetName.isNullOrEmpty() -> Charsets.UTF_8
            charsetName.equals("escape", ignoreCase = true) -> null
            else -> runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        }
        if (isQuery && charset != null) {
            if (encodedQuery(params)) {
                return params
            }
        }
        val len = params.length
        val sb = StringBuilder()
        var pos = 0
        while (pos <= len) {
            if (sb.isNotEmpty()) {
                sb.append("&")
            }
            var ampOffset = params.indexOf("&", pos)
            if (ampOffset == -1) {
                ampOffset = len
            }
            val eqOffset = params.indexOf("=", pos)
            val key: String
            val value: String?
            if (eqOffset == -1 || eqOffset > ampOffset) {
                key = params.substring(pos, ampOffset)
                value = null
            } else {
                key = params.substring(pos, eqOffset)
                value = params.substring(eqOffset + 1, ampOffset)
            }
            sb.appendEncoded(key, checkEncoded, charset)
            if (value != null) {
                sb.append("=")
                sb.appendEncoded(value, checkEncoded, charset)
            }
            pos = ampOffset + 1
        }
        return sb.toString()
    }

    private fun StringBuilder.appendEncoded(
        value: String,
        checkEncoded: Boolean,
        charset: Charset?,
    ) {
        if (checkEncoded && encodedForm(value)) {
            append(value)
        } else if (charset == null) {
            append(escape(value))
        } else {
            append(URLEncoder.encode(value, charset.name()).replace("+", "%20"))
        }
    }

    private fun Any?.toLegadoBoolean(default: Boolean = false): Boolean {
        return when (this) {
            null, JSONObject.NULL -> default
            is Boolean -> this
            is Number -> this.toInt() != 0
            is String -> this.isNotBlank() && !this.equals("false", ignoreCase = true) && this != "0"
            else -> true
        }
    }

    private fun Any?.toLegadoInt(): Int {
        return when (this) {
            null, JSONObject.NULL -> 0
            is Number -> this.toInt()
            is String -> this.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun Any?.toLegadoLong(): Long? {
        return when (this) {
            null, JSONObject.NULL -> null
            is Number -> this.toLong()
            is String -> this.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
            else -> null
        }
    }

    private fun Any?.toLegadoBodyString(): String? {
        return when (this) {
            null, JSONObject.NULL -> null
            is String -> this.takeIf { it.isNotBlank() }
            is JSONObject, is JSONArray -> this.toString()
            else -> this.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun Map<String, String>.findProxyHeader(): String? {
        return entries.firstOrNull { it.key.equals("proxy", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
    }

    private fun Map<String, String>.withoutProxyHeader(): Map<String, String> {
        if (isEmpty()) return this
        val proxyKey = keys.firstOrNull { it.equals("proxy", ignoreCase = true) } ?: return this
        return LinkedHashMap(this).apply { remove(proxyKey) }
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun looksLikeXml(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("<")
    }

    private fun escape(src: String): String {
        val tmp = StringBuilder()
        for (char in src) {
            val charCode = char.code
            if (charCode in 48..57 || charCode in 65..90 || charCode in 97..122) {
                tmp.append(char)
                continue
            }
            val prefix = when {
                charCode < 16 -> "%0"
                charCode < 256 -> "%"
                else -> "%u"
            }
            tmp.append(prefix).append(charCode.toString(16))
        }
        return tmp.toString()
    }

    private fun encodedQuery(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (QUERY_ALLOWED.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isHexDigit(c1) && isHexDigit(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    private fun encodedForm(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (FORM_ALLOWED.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isHexDigit(c1) && isHexDigit(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    private fun isHexDigit(c: Char): Boolean {
        return c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
    }

    private companion object {
        val QUERY_ALLOWED: BitSet = BitSet(256).apply {
            for (i in 'a'.code..'z'.code) set(i)
            for (i in 'A'.code..'Z'.code) set(i)
            for (i in '0'.code..'9'.code) set(i)
            for (char in "!$&()*+,-./:;=?@[\\]^_`{|}~") set(char.code)
        }

        val FORM_ALLOWED: BitSet = BitSet(256).apply {
            for (i in 'a'.code..'z'.code) set(i)
            for (i in 'A'.code..'Z'.code) set(i)
            for (i in '0'.code..'9'.code) set(i)
            for (char in "*-._") set(char.code)
        }
    }
}
