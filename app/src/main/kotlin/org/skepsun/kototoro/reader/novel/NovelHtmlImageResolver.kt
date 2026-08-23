package org.skepsun.kototoro.reader.novel

import org.jsoup.Jsoup
import java.net.URI

/**
 * T4A.2 小说 HTML 图片 URL 解析器。
 *
 * 处理经 [NovelHtmlNormalizer.sanitize] 清洗后 HTML 中的图片引用：
 * - [resolveImageSrc]：相对/协议相对 src → 绝对 URL；data:/file:/http(s) 原样；解析失败返回原值。
 * - [extractImageUrls]：收集 img 的 data-src（优先）/src，去重保序。
 *
 * 与 Jsoup Safelist 的配合：清洗时相对 src 已在有 baseUrl 的情况下被解析为绝对 URL，
 * 而 data-src 作为普通属性不受协议校验（保持相对），需要本类补一次 [resolveImageSrc]。
 */
object NovelHtmlImageResolver {

    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_DATA = "data"
    private const val SCHEME_FILE = "file"

    private val HTTP_SCHEMES = setOf(SCHEME_HTTP, SCHEME_HTTPS)
    private val DROPPED_SCHEMES = setOf("javascript", "vbscript")

    /**
     * 相对/协议相对 src → 绝对 URL；data:、file:、http(s): 原样。baseUrl 为 null 时相对返回原值。
     *
     * - `//host/path` → `baseUrl 的 scheme + src`
     * - `/path` → `baseUrl 的 origin + path`
     * - `../x`、`x` → 按 baseUrl 目录语义解析（java.net.URI.resolve）
     * - 解析失败返回原值
     */
    fun resolveImageSrc(src: String, baseUrl: String?): String {
        val trimmed = src.trim()
        if (trimmed.isBlank() || baseUrl.isNullOrBlank()) return src
        val scheme = trimmed.substringBefore(':').lowercase()
        if (scheme in HTTP_SCHEMES || scheme == SCHEME_DATA || scheme == SCHEME_FILE) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val baseScheme = runCatching { URI.create(baseUrl).scheme }.getOrNull()
            val effectiveScheme = baseScheme?.takeIf { it in HTTP_SCHEMES } ?: SCHEME_HTTPS
            return "$effectiveScheme:$trimmed"
        }
        return runCatching {
            URI.create(baseUrl).resolve(trimmed).toASCIIString()
        }.getOrDefault(src)
    }

    /**
     * 从（已清洗）HTML 中收集去重绝对图片 URL（含 data:），保序。
     *
     * 每个 `<img>` 优先取 `data-src`，缺失/为空时取 `src`；`javascript:`/`vbscript:` 等
     * 非图片协议一律丢弃（安全性兜底，正常清洗后已不存在）。
     */
    fun extractImageUrls(html: String, baseUrl: String? = null): List<String> {
        if (html.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        Jsoup.parse(html).select("img").forEach { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            if (src.isBlank()) return@forEach
            if (src.substringBefore(':').lowercase() in DROPPED_SCHEMES) return@forEach
            result.add(resolveImageSrc(src, baseUrl))
        }
        return result.toList()
    }
}

/**
 * [applyRetryResults] 的产出：重写后的 HTML + 叠加重试结果后的完整映射。
 */
internal data class RetryApplyResult(
    val html: String,
    val mapping: Map<String, String>,
)

/**
 * 单图重试结果的纯函数应用（T4A.5）：根据失败列表 + 重试成功集合生成新 HTML / 新 mapping。
 *
 * 语义：输入 [html] 是已按 [nameMap]（remoteUrl → 本地名）重写过、img src 为本地名的
 * HTML（即 DownloadWorker.rewriteHtmlWithCustomNames 的产物；data-src 保留远程原值）。
 * [retried]（remoteUrl → 最终本地名；成功重试为真实文件名、重试仍失败为 `failed_<n>.jpg`
 * 占位名）叠加进映射：把对应 img 的 src 改写为最终本地名，并返回叠加后的完整 mapping。
 *
 * 纯函数、无 IO，放置于此以便 JVM 单元测试直接覆盖（DownloadWorker 为 Android Worker 类，
 * 其 private 方法不可测）。
 */
internal fun applyRetryResults(
    html: String,
    nameMap: Map<String, String>,
    retried: Map<String, String>,
): RetryApplyResult {
    val finalByUrl = nameMap.toMutableMap()
    finalByUrl.putAll(retried)
    // 基础重写后 img src 已是本地名，这里按“老本地名 → 最终本地名”叠加
    val localToFinal = HashMap<String, String>(nameMap.size)
    for ((url, oldLocal) in nameMap) {
        localToFinal[oldLocal] = finalByUrl[url] ?: oldLocal
    }
    val newHtml = runCatching {
        val doc = Jsoup.parse(html)
        doc.select("img").forEach { img ->
            // 基础重写后 src 即本地名（优先按 src 匹配，data-src 仍是远程原值不属于叠加目标）
            val src = img.attr("src").ifBlank { img.attr("data-src") }.trim()
            val finalLocal = localToFinal[src]
            if (finalLocal != null) {
                img.attr("src", finalLocal)
                img.attr("referrerpolicy", "no-referrer")
            }
        }
        doc.outerHtml()
    }.getOrDefault(html)
    return RetryApplyResult(newHtml, finalByUrl)
}
