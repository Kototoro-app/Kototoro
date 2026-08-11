package org.skepsun.kototoro.core.parser.legado.book

import android.util.Log
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.*
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Handles search and explore list parsing using ruleSearch / ruleExplore.
 * 
 * Based on legado-with-MD3 BookList pattern.
 */
object BookList {

    private const val TAG = "LegadoBookList"

    private fun previewForLog(value: String, limit: Int = 160): String {
        val normalized = value.replace("\r", "").replace("\n", "\\n").trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "…"
    }
    
    /**
     * Parse book list from HTML/JSON content
     */
    fun parse(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        isSearch: Boolean,
        sandbox: LegadoSandbox
    ): List<Content> {
        val runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox)
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            isSearch = isSearch,
            runtimeContext = runtimeContext,
        )
    }

    fun parseWithRuntimeContext(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        isSearch: Boolean,
        runtimeContext: LegadoRuleRuntimeContext
    ): List<Content> {
        return parseInternal(
            content = content,
            baseUrl = baseUrl,
            source = source,
            config = config,
            isSearch = isSearch,
            runtimeContext = runtimeContext,
        )
    }

    private fun parseInternal(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        isSearch: Boolean,
        runtimeContext: LegadoRuleRuntimeContext
    ): List<Content> {
        val rule = if (isSearch) {
            config.ruleSearch
        } else {
            config.ruleExplore?.takeIf { !it.bookList.isNullOrBlank() } ?: config.ruleSearch
        }
        if (rule == null || rule.bookList.isNullOrBlank()) {
            Log.w(TAG, "bookList rule missing (isSearch=$isSearch) explore=${config.ruleExplore} search=${config.ruleSearch}")
            return emptyList()
        }

        if (isSearch) {
            val bookUrlPattern = config.bookUrlPattern
            if (!bookUrlPattern.isNullOrBlank()) {
                runCatching { Regex(bookUrlPattern) }
                    .getOrNull()
                    ?.takeIf { regex -> regex.matches(baseUrl) }
                    ?.let {
                        val detail = parseDetailAsSingleItem(content, baseUrl, source, config, runtimeContext)
                        if (detail != null) {
                            return listOf(detail)
                        }
                    }
            }
        }

        var reverse = false
        var listRule = rule.bookList
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.removePrefix("-")
        } else if (listRule.startsWith("+")) {
            listRule = listRule.removePrefix("+")
        }

        val analyzeRule = AnalyzeRule(content, runtimeContext, baseUrl)

        val items = analyzeRule.getElements(listRule)
        Log.d(
            TAG,
            "bookList ruleLen=${listRule.length} rule=${previewForLog(listRule)} items=${items.size} isSearch=$isSearch"
        )
        if (items.isEmpty()) {
            if (config.bookUrlPattern.isNullOrBlank()) {
                val detail = parseDetailAsSingleItem(content, baseUrl, source, config, runtimeContext)
                if (detail != null) {
                    return listOf(detail)
                }
            }
            Log.d(TAG, "bookList parse returned empty list for url=$baseUrl isSearch=$isSearch")
            return emptyList()
        }

        return parseItems(items, rule, baseUrl, source, runtimeContext, reverse)
    }

    private fun parseItems(
        items: List<Any?>,
        rule: org.skepsun.kototoro.core.model.jsonsource.SearchRule,
        baseUrl: String,
        source: ContentSource,
        runtimeContext: LegadoRuleRuntimeContext,
        reverse: Boolean
    ): List<Content> {
        val mangas = items.mapIndexedNotNull { index, item ->
            if (item == null) return@mapIndexedNotNull null
            // Create a new analyzer for the specific item
            val itemAnalyzer = AnalyzeRule(item, runtimeContext, baseUrl)
            
            val title = itemAnalyzer.getString(rule.name)
            val bookUrl = itemAnalyzer.getString(rule.bookUrl, isUrl = true)
            
            if (index < 3) {
                Log.d(TAG, "[parseItem $index] rule.name='${rule.name}' -> title='$title'")
                Log.d(TAG, "[parseItem $index] rule.bookUrl='${rule.bookUrl}' -> bookUrl='$bookUrl'")
                // 打印item的内容预览
                val itemStr = item.toString().take(200)
                Log.d(TAG, "[parseItem $index] item preview: $itemStr")
            }
            
            if (title.isBlank() || bookUrl.isBlank()) return@mapIndexedNotNull null
            
            val absoluteUrl = resolveUrl(baseUrl, bookUrl)
            val author = itemAnalyzer.getString(rule.author)
            val ruleCover = itemAnalyzer.getString(rule.coverUrl, isUrl = true)
            val coverUrl = ruleCover
                .takeIf { it.isNotBlank() }
                ?.let { LegadoUrlSanitizer.sanitizeImageUrl(resolveUrl(baseUrl, it.trim())) }
                ?.takeIf { it.isNotBlank() }
            val intro = itemAnalyzer.getString(rule.intro)
            
            Content(
                id = generateContentId(absoluteUrl),
                title = title,
                altTitles = emptySet(),
                url = absoluteUrl,
                publicUrl = absoluteUrl,
                rating = -1f,
                contentRating = null,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = if (author.isNotBlank()) setOf(author) else emptySet(),
                largeCoverUrl = null,
                description = intro,
                chapters = null,
                source = source
            )
        }

        val deduped = LinkedHashSet<Content>(mangas)
        val result = deduped.toMutableList()
        Log.d(TAG, "bookList parsed=${result.size} reverse=$reverse")
        if (reverse) {
            result.reverse()
        }
        return result
    }

    private fun parseDetailAsSingleItem(
        content: String,
        baseUrl: String,
        source: ContentSource,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
    ): Content? {
        val seed = Content(
            id = generateContentId(baseUrl),
            title = "",
            altTitles = emptySet(),
            url = baseUrl,
            publicUrl = baseUrl,
            rating = -1f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = null,
            source = source,
        )
        val result = BookInfo.parseWithRuntimeContext(
            manga = seed,
            content = content,
            baseUrl = baseUrl,
            config = config,
            runtimeContext = runtimeContext,
        )
        return result.manga.takeIf { it.title.isNotBlank() }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.isBlank() || relativeUrl.startsWith("http")) return relativeUrl
        return try {
            java.net.URL(java.net.URL(baseUrl), relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }

    private fun generateContentId(url: String): Long {
        return url.hashCode().toLong()
    }
}
