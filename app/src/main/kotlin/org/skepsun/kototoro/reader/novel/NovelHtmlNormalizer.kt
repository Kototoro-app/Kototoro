package org.skepsun.kototoro.reader.novel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

/**
 * T4A.1 小说 HTML 安全规范化器。
 *
 * 以 Jsoup [Safelist] 作为唯一安全边界，把任意（可能来自恶意扩展的）HTML 清洗为安全子集，
 * 替代 Phase 4A 之前的纯正则剥离（正则只能做表层过滤，无法可靠拦截 script/iframe/object、
 * on* 事件属性、javascript: 等注入）。
 *
 * 保留策略：
 * - 文本排版标签：p br b strong i em u s sub sup h1..h6 ul ol li blockquote pre code hr span a img
 * - 属性：a[href]、img[src/alt/title/data-src]（不做 class 白名单，安全起见不加 class）
 * - 协议：a[href] 仅 http/https；img[src] 仅 http/https/data（保留 data: base64 内联图以支持离线）
 * - script/style/iframe/object/embed/form/input 与全部 on* 事件属性由 Safelist 默认丢弃
 *
 * Jsoup.clean 语义（已实测验证）：
 * - 传入 [baseUrl] 时，相对 src/href 会解析为绝对 URL（http/https 协议方保留）。
 * - 不传 [baseUrl] 时，相对 src 因协议校验失败被丢弃；data-src 作为普通属性原样保留。
 * - 输出为 Body 内容（不带 html/head），prettyPrint(false) 保证文本不引入多余换行/缩进。
 *
 * 本清洗只作用于 HTML 源；NovelContentLoader 的纯文本出口与缓存行为保持不变。
 */
object NovelHtmlNormalizer {

    private val SAFELIST: Safelist = Safelist.none()
        .addTags(
            "p", "br", "b", "strong", "i", "em", "u", "s", "sub", "sup",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "pre", "code", "hr", "span", "a", "img",
        )
        .addAttributes("a", "href")
        .addAttributes("img", "src", "alt", "title", "data-src")
        .addProtocols("a", "href", "http", "https")
        .addProtocols("img", "src", "http", "https", "data")

    private val OUTPUT_SETTINGS: Document.OutputSettings = Document.OutputSettings().prettyPrint(false)

    /**
     * 把任意（可能恶意）HTML 清洗为安全 HTML 子集。
     *
     * @param html 待清洗的 HTML
     * @param baseUrl 可选基准 URL：用于把相对 src/href 解析为绝对 URL
     * @return 清洗后的安全 HTML（仅 Body 内容）
     */
    fun sanitize(html: String, baseUrl: String? = null): String {
        if (html.isBlank()) return ""
        return Jsoup.clean(html, baseUrl ?: "", SAFELIST, OUTPUT_SETTINGS)
    }

    /**
     * 把纯文本章节（本地 .txt）转成等价 HTML：空行分段为 `<p>`，段内换行为 `<br>`，`& < >` 转义。
     *
     * 本地章节统一按 HTML 处理（Jsoup 改写图片 src、再转纯文本），纯文本直接进入时空行只是
     * 无意义空白，整章会被折叠成一段，正文里的 `<` 也会被当成标签吞掉。
     */
    fun fromPlainText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\\n[ \\t]*\\n"))
        .map { it.trim('\n') }
        .filter { it.isNotBlank() }
        .joinToString("\n") { paragraph ->
            "<p>" + paragraph.lines().joinToString("<br>") { line -> escape(line.trim()) } + "</p>"
        }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
