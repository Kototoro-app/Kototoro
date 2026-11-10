package org.skepsun.kototoro.core.parser.legado.bridge

import android.util.Log
import org.mozilla.javascript.Undefined
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox

/**
 * 将 LegadoSandbox 适配为 AnalyzeRule 使用的 runtime 上下文。
 */
class LegadoSandboxRuleRuntimeContext(
    private val sandbox: LegadoSandbox,
) : LegadoRuleRuntimeContext {

    override fun ajax(url: String, baseUrl: String, ruleData: RuleDataInterface?): String? =
        androidLegadoRuleAjax(url, baseUrl, ruleData, this)

    override fun evalJs(script: String, result: Any?, baseUrl: String): Any? {
        sandbox.putVariable("baseUrl", normalizeBaseUrlForJavaScript(baseUrl))
        sandbox.putVariable("src", null)
        sandbox.setResult(result)
        val jsEvalResult = sandbox.eval(script)
        val resultFromContext = sandbox.getVariableAny("result")
        val srcFromContext = sandbox.getVariableAny("src")
        val finalResult = when {
            jsEvalResult != null && jsEvalResult !is Undefined &&
                (jsEvalResult !is String || jsEvalResult.isNotBlank()) -> jsEvalResult
            resultFromContext != null && resultFromContext !is Undefined &&
                resultFromContext.toString() != "undefined" -> resultFromContext
            srcFromContext != null && srcFromContext !is Undefined &&
                srcFromContext.toString() != "undefined" -> srcFromContext
            else -> jsEvalResult
        }
        Log.d(TAG, "[evalJs] evalOutput=${preview(jsEvalResult)} resultCtx=${preview(resultFromContext)} final=${preview(finalResult)}")
        return finalResult
    }

    override fun executeJs(script: String, result: Any?, baseUrl: String): Any? {
        sandbox.putVariable("baseUrl", normalizeBaseUrlForJavaScript(baseUrl))
        sandbox.putVariable("src", null)
        sandbox.setResult(result)
        return sandbox.execute(script)
    }

    override fun reGetBook() {
        sandbox.reGetBook()
    }

    override fun refreshTocUrl() {
        sandbox.refreshTocUrl()
    }

    override fun putVariable(key: String, value: String) {
        sandbox.putVariable(key, value)
    }

    override fun putVariableAny(key: String, value: Any?) {
        when (value) {
            null -> sandbox.putVariable(key, null)
            is String -> sandbox.putVariable(key, value)
            else -> sandbox.putVariable(key, value.toString())
        }
    }

    override fun getVariable(key: String): String? {
        return sandbox.getVariable(key)
    }

    override fun getVariableAny(key: String): Any? {
        return sandbox.getVariableAny(key)
    }

    override fun putSourceVariable(key: String, value: String): String {
        return sandbox.putSourceVariable(key, value)
    }

    override fun getSourceVariable(key: String): String {
        return sandbox.getSourceVariable(key)
    }

    override fun setBook(book: BookInfo?) {
        if (book == null) return
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
    }

    override fun getBook(): BookInfo? {
        val book = sandbox.getVariableAny("book") as? BookInfo
        if (book != null) return book
        val bookUrl = sandbox.getVariable("bookUrl") ?: return null
        return BookInfo(
            bookUrl = bookUrl,
            name = sandbox.getVariable("bookName"),
            author = sandbox.getVariable("bookAuthor"),
            tocUrl = sandbox.getVariable("tocUrl"),
        )
    }

    override fun setChapter(chapter: ChapterInfo?) {
        if (chapter == null) return
        sandbox.setChapter(
            LegadoSandbox.ChapterContext(
                title = chapter.name,
                url = chapter.chapterUrl,
                index = chapter.index,
            ),
        )
    }

    override fun getChapter(): ChapterInfo? {
        val chapter = sandbox.getVariableAny("chapter") as? ChapterInfo
        if (chapter != null) return chapter
        val chapterUrl = sandbox.getVariable("chapterUrl") ?: return null
        return ChapterInfo(
            chapterUrl = chapterUrl,
            name = sandbox.getVariable("chapterName").orEmpty(),
            index = sandbox.getVariable("chapterIndex")?.toIntOrNull() ?: 0,
        )
    }

    override fun getRuleData() = sandbox.getRuleData()

    override fun getHttpExecutor() = sandbox.getHttpExecutor()

    override fun getSourceObject(): Any {
        return sandbox.getSource()
    }

    override fun getSourceTag(): String {
        return sandbox.getSourceTag()
    }

    override fun getParserSourceName(): String? {
        return sandbox.getParserSourceName()
    }

    override fun <T> withJavaBridge(javaBridge: Any?, block: () -> T): T {
        return sandbox.withJavaBridge(javaBridge, block)
    }

    private fun normalizeBaseUrlForJavaScript(url: String): String {
        if (url.isBlank()) return url

        val trimmed = url.trim()
        val commaIndex = trimmed.indexOf(",{").let { if (it >= 0) it else trimmed.indexOf(", {") }
        val withoutOptions = if (commaIndex >= 0) trimmed.substring(0, commaIndex).trim() else trimmed

        val splitIndex = withoutOptions.indexOfAny(charArrayOf('?', '#'))
        val head = if (splitIndex >= 0) withoutOptions.substring(0, splitIndex) else withoutOptions
        val tail = if (splitIndex >= 0) withoutOptions.substring(splitIndex) else ""

        if (head.endsWith("/")) return head + tail
        if (head.matches(Regex(".*/\\d+"))) return "$head/$tail"
        return head + tail
    }

    private fun preview(value: Any?, limit: Int = 120): String {
        if (value == null) return "null"
        val text = value.toString()
        return if (text.length <= limit) text else text.take(limit) + "…"
    }

    private companion object {
        private const val TAG = "LegadoRuleRuntimeCtx"
    }
}
