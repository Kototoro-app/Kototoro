package org.skepsun.kototoro.core.parser.legado.runtime

/**
 * WebView 抓取请求模型。
 */
data class LegadoWebViewRequest(
    val url: String? = null,
    val html: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val javaScript: String? = null,
    val delayMs: Long = 0,
)
