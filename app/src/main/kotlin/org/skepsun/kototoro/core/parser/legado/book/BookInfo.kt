package org.skepsun.kototoro.core.parser.legado.book

import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.javascript.BookInfo as JsBookInfo
import org.skepsun.kototoro.core.parser.legado.*
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag

/**
 * Handles book details parsing using ruleBookInfo.
 */
object BookInfo {

    private val nameRegex = Regex("\\s+作\\s*者.*|\\s+\\S+\\s+著")
    private val authorRegex = Regex("^\\s*作\\s*者[:：\\s]+|\\s+著")

    data class Result(
        val manga: Content,
        val tocUrl: String?,
        val isWebFile: Boolean = false,
    )

    /**
     * Parse book details from HTML/JSON content
     */
    fun parse(
        manga: Content,
        content: String,
        baseUrl: String,
        config: LegadoBookSource,
        sandbox: LegadoSandbox
    ): Result {
        val rule = config.ruleBookInfo ?: return Result(manga, null)
        val runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox)
        return parseInternal(
            manga = manga,
            content = content,
            baseUrl = baseUrl,
            config = config,
            runtimeContext = runtimeContext,
            updateBookContext = { book ->
                sandbox.setBook(
                    LegadoSandbox.BookContext(
                        name = book.name.orEmpty(),
                        author = book.author.orEmpty(),
                        url = book.bookUrl,
                        coverUrl = book.coverUrl.orEmpty(),
                        intro = book.intro.orEmpty(),
                        kind = book.kind.orEmpty(),
                        lastChapter = book.lastChapter.orEmpty(),
                        tocUrl = book.tocUrl.orEmpty(),
                        wordCount = book.wordCount.orEmpty(),
                    ),
                )
            },
        )
    }

    fun parseWithRuntimeContext(
        manga: Content,
        content: String,
        baseUrl: String,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
    ): Result {
        return parseInternal(
            manga = manga,
            content = content,
            baseUrl = baseUrl,
            config = config,
            runtimeContext = runtimeContext,
            updateBookContext = { book ->
                (runtimeContext as? StandaloneLegadoRuleRuntimeContext)?.setBook(book)
            },
        )
    }

    private fun parseInternal(
        manga: Content,
        content: String,
        baseUrl: String,
        config: LegadoBookSource,
        runtimeContext: LegadoRuleRuntimeContext,
        updateBookContext: (JsBookInfo) -> Unit,
    ): Result {
        val rule = config.ruleBookInfo ?: return Result(manga, null)
        val analyzeRule = AnalyzeRule(
            content = content,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
            fromBookInfo = true,
        )

        if (!rule.init.isNullOrBlank()) {
            analyzeRule.setContent(analyzeRule.getElement(rule.init!!))
        }

        // Update sandbox context with current book info
        updateBookContext(
            JsBookInfo(
                bookUrl = manga.url,
                name = manga.title,
                author = manga.authors.firstOrNull() ?: "",
            ),
        )

        val allowRename = !rule.canReName.isNullOrBlank()
        val parsedTitle = formatBookName(analyzeRule.getString(rule.name)).takeIf { it.isNotBlank() }
        val parsedAuthor = formatBookAuthor(analyzeRule.getString(rule.author)).takeIf { it.isNotBlank() }
        val title = when {
            parsedTitle.isNullOrBlank() -> manga.title
            manga.title.isBlank() || allowRename -> parsedTitle
            else -> manga.title
        }
        val author = when {
            parsedAuthor.isNullOrBlank() -> manga.authors.firstOrNull() ?: ""
            manga.authors.isEmpty() || allowRename -> parsedAuthor
            else -> manga.authors.firstOrNull() ?: ""
        }
        val coverFromRule = analyzeRule.getString(rule.coverUrl, isUrl = true)
        val coverUrl = coverFromRule
            .takeIf { it.isNotBlank() }
            ?.let { LegadoUrlSanitizer.sanitizeImageUrl(resolveUrl(baseUrl, it)).takeIf { u -> u.isNotBlank() } }
            ?: manga.coverUrl
        val intro = analyzeRule.getString(rule.intro).takeIf { it.isNotBlank() } ?: manga.description
        val lastChapter = analyzeRule.getString(rule.lastChapter).takeIf { it.isNotBlank() }
        val wordCount = formatWordCount(analyzeRule.getString(rule.wordCount)).takeIf { it.isNotBlank() }
        val kindList = analyzeRule.getStringList(rule.kind)
        val kind = kindList
            ?.joinToString(",")
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val hasDownloadRule = !rule.downloadUrls.isNullOrBlank()
        val downloadUrls = if (hasDownloadRule) {
            analyzeRule.getStringList(rule.downloadUrls, isUrl = true).orEmpty()
        } else {
            emptyList()
        }
        val isWebFile = hasDownloadRule
        if (isWebFile && downloadUrls.isEmpty()) {
            throw ParseException("下载链接为空", baseUrl)
        }
        var tocUrl = if (isWebFile) {
            null
        } else {
            analyzeRule.getString(rule.tocUrl, isUrl = true).takeIf { it.isNotBlank() } ?: baseUrl
        }

        val source = manga.source
        val tags = if (kind.isNotBlank()) {
            kind.split(",", " ", "|").map { it.trim() }.filter { it.isNotBlank() }.map { ContentTag(it, it, source) }.toSet()
        } else {
            manga.tags
        }

        val updated = manga.copy(
            title = title,
            authors = if (author.isNotBlank()) setOf(author) else manga.authors,
            coverUrl = coverUrl,
            description = intro,
            tags = tags
        )

        // 更新 sandbox 中的目录上下文
        updateBookContext(
            JsBookInfo(
                bookUrl = updated.url,
                name = updated.title,
                author = updated.authors.firstOrNull() ?: "",
                coverUrl = updated.coverUrl,
                intro = updated.description,
                kind = kind,
                lastChapter = lastChapter,
                tocUrl = tocUrl,
                wordCount = wordCount,
            ),
        )

        if (downloadUrls.isNotEmpty()) {
            runtimeContext.putVariable("downloadUrls", downloadUrls.joinToString("\n"))
        }
        if (!isWebFile && tocUrl == baseUrl) {
            runtimeContext.putVariable("tocHtml", content)
        }

        return Result(
            manga = updated,
            tocUrl = tocUrl,
            isWebFile = isWebFile,
        )
    }

    private fun formatBookName(name: String): String {
        return name.replace(nameRegex, "").trim()
    }

    private fun formatBookAuthor(author: String): String {
        return author.replace(authorRegex, "").trim()
    }

    private fun formatWordCount(wordCount: String): String {
        if (wordCount.isBlank()) return ""
        val trimmed = wordCount.trim()
        val words = trimmed.toIntOrNull() ?: return trimmed
        if (words <= 0) return ""
        return if (words > 10000) {
            val value = java.text.DecimalFormat("#.#").format(words.toDouble() / 10000.0)
            "${value}万字"
        } else {
            "${words}字"
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.isBlank() || relativeUrl.startsWith("http")) return relativeUrl
        return try {
            java.net.URL(java.net.URL(baseUrl), relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}
