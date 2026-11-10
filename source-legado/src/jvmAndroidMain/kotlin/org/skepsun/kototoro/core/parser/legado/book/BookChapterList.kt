package org.skepsun.kototoro.core.parser.legado.book

import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.*
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleContextProvider
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Handles Table of Contents (TOC) parsing using ruleToc.
 */
object BookChapterList {

    private const val TAG = "LegadoRepository"

    data class ParseResult(

        val chapters: List<ContentChapter>,
        val nextPageUrls: List<String>,
        /**
         * 是否需要对当前页解析出的章节列表做反转。
         *
         * legado 规则约定：`chapterList` 以 '-' 开头表示“反转章节列表”。
         * `chapterList` 以 '+' 开头表示“显式不反转”（等同于无前缀，但可用于自解释）。
         */
        val shouldReverse: Boolean
    )

    /**
     * Parse chapter list from HTML/JSON content
     */
    fun parse(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        sandbox: LegadoRuleContextProvider
    ): ParseResult {
        val runtimeContext = sandbox.createRuleRuntimeContext()
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
        )
    }

    fun parseWithRuntimeContext(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
    ): ParseResult {
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            runtimeContext = runtimeContext,
        )
    }

    private fun parseInternal(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
    ): ParseResult {
        val parseStart = LegadoRuleClock.elapsedRealtime()
        LegadoRuleLog.debug(TAG, "===== BookChapterList.parse START =====")
        LegadoRuleLog.debug(TAG, "baseUrl=$baseUrl")

        val rule = config.ruleToc
        if (rule == null) {
            LegadoRuleLog.warn(TAG, "ruleToc is null!")
            return ParseResult(emptyList(), emptyList(), shouldReverse = false)
        }

        LegadoRuleLog.debug(TAG, "ruleToc.chapterList=${rule.chapterList}")
        LegadoRuleLog.debug(TAG, "ruleToc.chapterName=${rule.chapterName}")
        LegadoRuleLog.debug(TAG, "ruleToc.chapterUrl=${rule.chapterUrl}")
        LegadoRuleLog.debug(TAG, "ruleToc.nextTocUrl=${rule.nextTocUrl}")

        if (rule.chapterList.isNullOrBlank()) {
            LegadoRuleLog.warn(TAG, "chapterList rule is blank!")
            return ParseResult(emptyList(), emptyList(), shouldReverse = false)
        }

        var listRule = rule.chapterList.orEmpty()
        var shouldReverse = false
        if (listRule.startsWith("-")) {
            shouldReverse = true
            listRule = listRule.removePrefix("-")
        } else if (listRule.startsWith("+")) {
            listRule = listRule.removePrefix("+")
        }
        LegadoRuleLog.debug(TAG, "listRule (after prefix processing)=$listRule, shouldReverse=$shouldReverse")
        LegadoRuleLog.debug(TAG, "ruleToc.formatJs=${rule.formatJs?.takeIf { it.isNotBlank() }?.let { "<present>" } ?: "<blank>"}")

        val analyzeRule = AnalyzeRule(content, runtimeContext, baseUrl)
        val listStart = LegadoRuleClock.elapsedRealtime()
        val items = analyzeRule.getElements(listRule)
        val listCost = LegadoRuleClock.elapsedRealtime() - listStart
        LegadoRuleLog.debug(TAG, "Found ${items.size} elements with listRule")
        LegadoRuleLog.debug(TAG, "listRule parsing took ${listCost}ms")

        val nextPageStart = LegadoRuleClock.elapsedRealtime()
        val nextPageUrls = rule.nextTocUrl
            ?.let {
                LegadoRuleLog.debug(TAG, "Evaluating nextTocUrl rule: $it")
                analyzeRule.getStringList(it, isUrl = true)
            }
            ?.mapNotNull { it.takeIf { url -> url.isNotBlank() } }
            ?: emptyList()
        val nextPageCost = LegadoRuleClock.elapsedRealtime() - nextPageStart
        LegadoRuleLog.debug(TAG, "nextPageUrls=$nextPageUrls")
        LegadoRuleLog.debug(TAG, "nextTocUrl parsing took ${nextPageCost}ms")

        var volumeIndex = 0
        val chapterMapStart = LegadoRuleClock.elapsedRealtime()
        val chapters = items.mapIndexedNotNull { index, item ->
            val itemAnalyzer = AnalyzeRule(item, runtimeContext, baseUrl)
            val runtimeChapter = ChapterInfo(
                chapterUrl = "",
                name = "",
                index = index + 1,
            )
            runtimeContext.setChapter(runtimeChapter)
            runtimeContext.putVariable("index", (index + 1).toString())

            val name = itemAnalyzer.getString(rule.chapterName)
            if (name.isNotBlank()) {
                runtimeChapter.name = name
                runtimeChapter.putVariable("title", name)
                runtimeContext.putVariable("chapterName", name)
                runtimeContext.putVariable("title", name)
                runtimeContext.setChapter(runtimeChapter)
            }
            val isVolume = rule.isVolume
                ?.let { itemAnalyzer.getString(it) }
                ?.trim()
                ?.let { value ->
                    value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)
                }
                ?: false
            var url = itemAnalyzer.getString(rule.chapterUrl, isUrl = true)

            if (url.isBlank() && isVolume && name.isNotBlank()) {
                url = name + index
                LegadoRuleLog.debug(TAG, "[TOC] Volume[$index] chapterUrl empty, falling back to synthetic url")
            } else if (rule.chapterUrl.isNullOrBlank() && url.isBlank() && name.isNotBlank()) {
                url = baseUrl
                LegadoRuleLog.debug(TAG, "[TOC] Chapter[$index] chapterUrl empty, falling back to baseUrl")
            }

            if (url.isNotBlank()) {
                runtimeChapter.putVariable("chapterUrl", url)
                runtimeContext.putVariable("chapterUrl", url)
                runtimeContext.setChapter(runtimeChapter.copy(chapterUrl = url))
            }

            val absoluteUrl = resolveUrl(baseUrl, url)

            if (index < 5) {
                LegadoRuleLog.debug(TAG, "[TOC] Chapter[$index] name=\"$name\", url=\"$url\" -> resolved=\"$absoluteUrl\"")
            }

            if (name.isBlank() || url.isBlank()) {
                LegadoRuleLog.debug(TAG, "Skipping chapter due to blank name or url: name=\"$name\", url=\"$url\"")
                return@mapIndexedNotNull null
            }

            val normalizedUrl = LegadoUrlRules.normalizeUrl(absoluteUrl)
            val stableId = (source.name.hashCode().toLong() shl 32) + (normalizedUrl.hashCode().toLong() and 0xFFFFFFFFL)

            if (index < 5) {
                LegadoRuleLog.debug(TAG, "[TOC] Chapter[$index] name=\"$name\", stableId=$stableId, sourceName=${source.name}(hash=${source.name.hashCode()}), url=\"$absoluteUrl\", normalized=\"$normalizedUrl\"(hash=${normalizedUrl.hashCode()})")
            }
            if ((index + 1) % 200 == 0) {
                val elapsed = LegadoRuleClock.elapsedRealtime() - chapterMapStart
                LegadoRuleLog.debug(
                    TAG,
                    "[TOC] Progress ${index + 1}/${items.size}, elapsed=${elapsed}ms, avg=${elapsed / (index + 1)}ms/item",
                )
            }

            val finalUrl = absoluteUrl
            val vipMark = rule.isVip
                ?.let { itemAnalyzer.getString(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "0" && !it.equals("false", ignoreCase = true) }
            val payMark = rule.isPay
                ?.let { itemAnalyzer.getString(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "0" && !it.equals("false", ignoreCase = true) }

            val branchTag = buildString {
                if (vipMark != null) append(if (isNotEmpty()) ",vip" else "vip")
                if (payMark != null) append(if (isNotEmpty()) ",pay" else "pay")
            }.takeIf { it.isNotEmpty() }

            val uploadDate = rule.updateTime
                ?.let { itemAnalyzer.getString(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parseTimestamp(it) }
                ?: 0L

            if (isVolume) {
                volumeIndex++
            }

            ContentChapter(
                id = stableId,
                title = name,
                number = if (isVolume) 0f else index.toFloat() + 1f,
                volume = if (isVolume) volumeIndex else 0,
                url = finalUrl,
                scanlator = null,
                uploadDate = uploadDate,
                branch = branchTag,
                source = source
            )
        }
        val chapterMapCost = LegadoRuleClock.elapsedRealtime() - chapterMapStart

        LegadoRuleLog.debug(TAG, "Parsed ${chapters.size} chapters")
        LegadoRuleLog.debug(TAG, "chapter materialization took ${chapterMapCost}ms")
        LegadoRuleLog.debug(TAG, "===== BookChapterList.parse END (${LegadoRuleClock.elapsedRealtime() - parseStart}ms) =====")
        LegadoRuleLog.debug(TAG, "===== BookChapterList.parse END =====")

        return ParseResult(chapters, nextPageUrls, shouldReverse)
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.isBlank() || relativeUrl.startsWith("http")) return relativeUrl
        return try {
            java.net.URL(java.net.URL(baseUrl), relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }

    private fun parseTimestamp(text: String): Long {
        if (text.isBlank()) return 0L
        // Try numeric millis
        text.toLongOrNull()?.let { return it }
        // Try common formats
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy.MM.dd",
            "MM-dd",
            "MM/dd",
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(text)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }
}
