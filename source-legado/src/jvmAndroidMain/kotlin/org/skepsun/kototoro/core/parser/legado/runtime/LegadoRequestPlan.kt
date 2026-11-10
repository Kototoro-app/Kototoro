package org.skepsun.kototoro.core.parser.legado.runtime

/**
 * Legado 规则解析后的标准请求计划。
 *
 * 该模型用于承接 AnalyzeUrl 的输出，并作为后续 HTTP/WebView 执行器的统一输入。
 */
data class LegadoRequestPlan(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val bodyIsForm: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val enableCookieJar: Boolean = true,
    val charsetName: String? = null,
    val useWebView: Boolean = false,
    val webJs: String? = null,
    val sourceRegex: String? = null,
    val overrideUrlRegex: String? = null,
    val webViewDelayTime: Long = 0,
    val retry: Int = 0,
    val type: String? = null,
    val bodyJs: String? = null,
    val js: String? = null,
    val dnsIp: String? = null,
    val serverId: Long? = null,
    val proxy: String? = null,
    val readTimeoutMs: Long? = null,
    val callTimeoutMs: Long? = null,
)
