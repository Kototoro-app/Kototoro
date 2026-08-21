package org.skepsun.kototoro.core.parser.legado.book

import org.json.JSONObject
import org.jsoup.parser.Parser
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.model.jsonsource.ContentRule
import org.skepsun.kototoro.core.parser.legado.AnalyzeRule
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.parsers.exception.ContentUnavailableException
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Handles chapter content parsing and image extraction.
 */
object BookContent {

    const val HEADER_IMAGE_DECODE = "X-Legado-ImageDecode"

    private const val TAG = "LegadoRepository"
    private val optionsSplitRegex = Regex("\\s*,\\s*(?=\\{)")

    data class ParseResult(
        val pages: List<ContentPage>,
        val nextPageUrls: List<String>
    )

    data class NovelPageParseResult(
        val content: String,
        val nextPageUrls: List<String>,
    )
    
    fun parse(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        sandbox: LegadoSandbox,
        nextChapterUrl: String? = null,
    ): ParseResult {
        val runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox)
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
            nextChapterUrl = nextChapterUrl,
        )
    }

    fun parseWithRuntimeContext(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        nextChapterUrl: String? = null,
    ): ParseResult {
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
            nextChapterUrl = nextChapterUrl,
        )
    }

    fun parseNovelPage(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        sandbox: LegadoSandbox,
        nextChapterUrl: String? = null,
    ): NovelPageParseResult {
        val runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox)
        return parseNovelPageInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
            nextChapterUrl = nextChapterUrl,
        )
    }

    fun parseNovelPageWithRuntimeContext(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        nextChapterUrl: String? = null,
    ): NovelPageParseResult {
        return parseNovelPageInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
            nextChapterUrl = nextChapterUrl,
        )
    }

    fun finalizeNovelChapter(
        pageContents: List<String>,
        baseUrl: String,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        nextChapterUrl: String? = null,
    ): String {
        val contentRule = config.ruleContent ?: return pageContents.joinToString("\n").trim()
        val contentStr = pageContents.joinToString("\n").trim()
        if (contentStr.isBlank()) return ""
        return applyReplace(
            content = contentStr,
            rule = contentRule,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
            nextChapterUrl = nextChapterUrl,
        ).joinToString("\n").trim()
    }

    private fun parseInternal(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        nextChapterUrl: String?,
    ): ParseResult {
        val rule = config.ruleContent ?: return ParseResult(emptyList(), emptyList())
        val analyzeRule = AnalyzeRule(content, runtimeContext, baseUrl).setNextChapterUrl(nextChapterUrl)
        val chapter = runtimeContext.getChapter()

        // 避免打印超长（多行）规则导致 logcat 刷屏；仅保留长度信息便于定位。
        val ruleContent = rule.content.orEmpty()
        android.util.Log.d(
            TAG,
            "[BookContent] Rule length=${ruleContent.length}, HTML length=${content.length}, Type=${config.bookSourceType}"
        )
        
        val titleFromContent = rule.title
            ?.takeIf { it.isNotBlank() }
            ?.let { analyzeRule.getString(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (titleFromContent != null) {
            runtimeContext.putVariable("chapterName", titleFromContent)
            runtimeContext.putVariable("title", titleFromContent)
            chapter?.name = titleFromContent
            runtimeContext.setChapter(chapter)
        }

        val rawPrimaryContent = analyzeRule.getString(ruleContent, unescape = false)
        android.util.Log.d(TAG, "[BookContent] Extracted primary content length=${rawPrimaryContent.length}")

        rule.subContent
            ?.takeIf { it.isNotBlank() }
            ?.let { subRule ->
                val subContent = analyzeRule.getString(subRule, unescape = false).trim()
                android.util.Log.d(TAG, "[BookContent] Extracted subContent length=${subContent.length}")
                if (subContent.isNotBlank()) {
                    runtimeContext.putVariable("subContent", subContent)
                    chapter?.putVariable("lyric", subContent)
                    runtimeContext.setChapter(chapter)
                }
            }

        android.util.Log.d(TAG, "[BookContent.applyReplace] BEFORE: replaceStr=${rule.replaceRegex?.take(120)}")

        val processedContent = applyReplace(
            content = rawPrimaryContent,
            rule = rule,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
            nextChapterUrl = nextChapterUrl,
        )

        val pages = ArrayList<ContentPage>()
        val isContent = config.bookSourceType == 2
        val imageDecodeScript = rule.imageDecode

        android.util.Log.d(TAG, "[BookContent] AFTER replace: isContent=$isContent pageCount=${processedContent.size}, first100=${processedContent.firstOrNull()?.take(100)}")

        if (isContent) {
            // For manga, merge all items and perform deep image extraction (Legado-style)
            val fullContent = processedContent.joinToString("\n")
            val doc = org.jsoup.Jsoup.parse(fullContent)
            val imgs = doc.select("img")
            
            if (imgs.isNotEmpty()) {
                imgs.forEach { img ->
                    val urlRaw = img.attr("data-original").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("src").takeIf { it.isNotBlank() }
                        ?: return@forEach

                    val (url, headers) = splitUrlAndHeaders(urlRaw)
                    if (url.isBlank()) return@forEach
                    val absoluteUrl = normalizeContentImageUrl(resolveUrl(baseUrl, url))
                    if (absoluteUrl.isBlank()) return@forEach
                    val finalHeaders = mergeDefaultImageHeaders(headers, baseUrl, absoluteUrl)
                    val pageHeaders = if (!imageDecodeScript.isNullOrBlank()) {
                        finalHeaders + (HEADER_IMAGE_DECODE to imageDecodeScript)
                    } else finalHeaders
                    pages.add(ContentPage(
                        id = (source.name.hashCode().toLong() shl 32) + pages.size,
                        url = absoluteUrl,
                        preview = absoluteUrl,
                        headers = pageHeaders,
                        source = source
                    ))
                }
                android.util.Log.d(TAG, "[BookContent] Deeply extracted ${pages.size} image URLs from HTML block")
            } else {
                // If no <img> found, fall back to interpreting items as raw URLs (for compatibility)
            processedContent.forEach { raw ->
                val candidate = raw.trim()
                if (candidate.isBlank()) return@forEach
                if (isLikelyUrl(candidate)) {
                        val (url, headers) = splitUrlAndHeaders(candidate)
                        val absoluteUrl = normalizeContentImageUrl(resolveUrl(baseUrl, url))
                        val finalHeaders = mergeDefaultImageHeaders(headers, baseUrl, absoluteUrl)
                        val fallbackPageHeaders = if (!imageDecodeScript.isNullOrBlank()) {
                            finalHeaders + (HEADER_IMAGE_DECODE to imageDecodeScript)
                        } else finalHeaders
                        pages.add(ContentPage(
                            id = (source.name.hashCode().toLong() shl 32) + pages.size,
                            url = absoluteUrl,
                            preview = absoluteUrl,
                            headers = fallbackPageHeaders,
                            source = source
                        ))
                    }
                }
                android.util.Log.d(TAG, "[BookContent] Extracted ${pages.size} raw URLs from result list")
            }
        } else {
            // Novel mode: proceed as individual text items
            processedContent.forEach { raw ->
                val refined = refineContent(raw, config)
                if (refined.isBlank()) return@forEach
                
                val isUrl = isLikelyUrl(refined)
                val absoluteUrl = if (isUrl) {
                    resolveUrl(baseUrl, refined)
                } else {
                    // Wrap text content in data URL for NovelContentLoader
                    val base64 = android.util.Base64.encodeToString(
                        refined.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    "data:text/html;base64,$base64"
                }

                pages.add(ContentPage(
                    id = (source.name.hashCode().toLong() shl 32) + pages.size,
                    url = absoluteUrl,
                    preview = if (isUrl) absoluteUrl else "TEXT",
                    headers = emptyMap(),
                    source = source
                ))
            }
        }
        
            if (pages.isNotEmpty()) {
                android.util.Log.d(TAG, "[Content] Page[0] URL: ${pages[0].url}")
            } else if (isContent) {
                detectApiErrorMessage(content)?.let { message ->
                    throw ContentUnavailableException(message)
                }
            }

        val nextPageUrls = rule.nextContentUrl
            ?.let { analyzeRule.getStringList(it, isUrl = true) }
            ?.mapNotNull { it.takeIf { url -> url.isNotBlank() } }
            ?: emptyList()
        
        if (nextPageUrls.isNotEmpty()) {
            android.util.Log.d(TAG, "[Content] nextPageUrls found: ${nextPageUrls.take(3)}")
        }

        return ParseResult(pages, nextPageUrls)
    }

    private fun parseNovelPageInternal(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        nextChapterUrl: String?,
    ): NovelPageParseResult {
        val rule = config.ruleContent ?: return NovelPageParseResult("", emptyList())
        val analyzeRule = AnalyzeRule(content, runtimeContext, baseUrl).setNextChapterUrl(nextChapterUrl)
        val chapter = runtimeContext.getChapter()

        rule.title
            ?.takeIf { it.isNotBlank() }
            ?.let { analyzeRule.getString(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { titleFromContent ->
                runtimeContext.putVariable("chapterName", titleFromContent)
                runtimeContext.putVariable("title", titleFromContent)
                chapter?.name = titleFromContent
                runtimeContext.setChapter(chapter)
            }

        val rawPrimaryContent = sanitizeNovelHtml(
            analyzeRule.getString(rule.content.orEmpty(), unescape = false),
        )
        rule.subContent
            ?.takeIf { it.isNotBlank() }
            ?.let { subRule ->
                val subContent = sanitizeNovelHtml(
                    analyzeRule.getString(subRule, unescape = false),
                ).trim()
                if (subContent.isNotBlank()) {
                    runtimeContext.putVariable("subContent", subContent)
                    chapter?.putVariable("lyric", subContent)
                    runtimeContext.setChapter(chapter)
                }
            }

        val nextPageUrls = rule.nextContentUrl
            ?.let { analyzeRule.getStringList(it, isUrl = true) }
            ?.mapNotNull { it.takeIf { url -> url.isNotBlank() } }
            ?: emptyList()

        if (rawPrimaryContent.isBlank()) {
            detectApiErrorMessage(content)?.let { message ->
                throw ContentUnavailableException(message)
            }
        }

        return NovelPageParseResult(
            content = rawPrimaryContent,
            nextPageUrls = nextPageUrls,
        )
    }

    private fun sanitizeNovelHtml(rawContent: String): String {
        if (rawContent.isBlank()) return ""
        val sanitized = rawContent
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("<[^>]+>"), "")
        val unescaped = if (sanitized.indexOf('&') >= 0) {
            Parser.unescapeEntities(sanitized, false)
        } else {
            sanitized
        }
        return unescaped
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    private fun normalizeContentImageUrl(url: String): String {
        if (url.isBlank()) return url
        // Android 9+ 默认可能禁用 cleartext；对 mkzcdn 等常见图床统一升级 https。
        if (url.startsWith("http://", ignoreCase = true)) {
            val host = runCatching { java.net.URL(url).host.orEmpty() }.getOrDefault("")
            if (host.endsWith("mkzcdn.com", ignoreCase = true)) {
                return "https://" + url.removePrefix("http://")
            }
        }
        return url
    }

    private fun mergeDefaultImageHeaders(
        original: Map<String, String>,
        baseUrl: String,
        imageUrl: String
    ): Map<String, String> {
        val host = runCatching { java.net.URL(imageUrl).host.orEmpty() }.getOrDefault("")
        if (!host.endsWith("mkzcdn.com", ignoreCase = true)) return original

        val lower = original.keys.associateBy { it.lowercase() }
        if (lower.containsKey("referer")) return original

        val referer = runCatching {
            val u = java.net.URL(baseUrl)
            "${u.protocol}://${u.host}/"
        }.getOrNull() ?: "https://comic.mkzhan.com/"

        return buildMap {
            putAll(original)
            put("Referer", referer)
        }
    }

    private fun refineContent(content: String, config: LegadoBookSource): String {
        return content.trim()
    }

    /**
     * Resolve Legado template variables in a string (e.g. {{book.durChapterTitle}}).
     * Variables are looked up from the sandbox context.
     */
    private fun applyReplace(
        content: String,
        rule: ContentRule,
        runtimeContext: LegadoRuleRuntimeContext,
        baseUrl: String,
        nextChapterUrl: String?,
    ): List<String> {
        val normalized = content
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
        val useReplaceRule = runtimeContext.getBook()?.getUseReplaceRule() != false
        if (!useReplaceRule || rule.replaceRegex.isNullOrBlank()) {
            return normalized
                .takeIf { it.isNotBlank() }
                ?.let(::listOf)
                ?: emptyList()
        }
        val analyzeRule = AnalyzeRule(
            content = normalized,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
        ).setNextChapterUrl(nextChapterUrl)
        val replaced = analyzeRule.getString(rule.replaceRegex, mContent = normalized)
        val finalText = replaced
            .lines()
            .joinToString("\n") { line ->
                if (line.isBlank()) "" else "　　${line.trim()}"
            }
            .trim()

        return finalText
            .takeIf { it.isNotBlank() }
            ?.let(::listOf)
            ?: emptyList()
    }

    private fun isLikelyUrl(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.length > 2048) return false // Too long for a URL
        val lower = trimmed.lowercase()
        return lower.startsWith("http://") || 
               lower.startsWith("https://") || 
               lower.startsWith("data:") || 
               lower.startsWith("file://") ||
               lower.startsWith("ftp://") ||
               (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.contains("\n"))
    }

        private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
            if (relativeUrl.isBlank() || relativeUrl.startsWith("http")) return relativeUrl
            return try {
                java.net.URL(java.net.URL(baseUrl), relativeUrl).toString()
            } catch (e: Exception) {
                relativeUrl
            }
        }

        private fun detectApiErrorMessage(content: String): String? {
            val trimmed = content.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

            val codeValue = obj.opt("code")?.toString()?.trim().orEmpty()
            val statusValue = obj.opt("status")?.toString()?.trim().orEmpty()
            val message = (obj.optString("message").ifBlank { obj.optString("msg") }).trim()
            if (message.isBlank()) return null

            // 常见约定：
            // - code: 0 / success
            // - status: 0 / 200 / ok
            val isSuccess = codeValue == "0" ||
                codeValue.equals("success", ignoreCase = true) ||
                statusValue == "0" ||
                statusValue == "200" ||
                statusValue.equals("ok", ignoreCase = true)
            return if (isSuccess) null else message
        }

        private fun splitUrlAndHeaders(raw: String): Pair<String, Map<String, String>> {
            val splitMatch = optionsSplitRegex.find(raw)
            if (splitMatch == null) return raw.trim() to emptyMap()

            val urlPart = raw.substring(0, splitMatch.range.first).trim()
            val optionsPart = raw.substring(splitMatch.range.last + 1).trim()

            val headers = runCatching {
                val normalizedOptions = if (optionsPart.contains("'")) {
                    optionsPart.replace("'", "\"")
                } else optionsPart
                val optionsJson = JSONObject(normalizedOptions)
                val headersObj = optionsJson.optJSONObject("headers") ?: return@runCatching emptyMap()
                buildMap<String, String> {
                    headersObj.keys().forEach { key ->
                        put(key, headersObj.optString(key))
                    }
                }
            }.getOrDefault(emptyMap())

            return urlPart to headers
        }
}
