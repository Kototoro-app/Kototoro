package org.skepsun.kototoro.core.parser.legado.runtime

/**
 * 运行时统一 HTTP 响应模型。
 *
 * 与 JS bridge 里的 StrResponse 语义保持接近，但补充状态码与响应头，便于后续复用。
 */
data class LegadoHttpResponse(
    val url: String,
    val body: String,
    val code: Int? = null,
    val headers: Map<String, String> = emptyMap(),
)
