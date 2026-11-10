package org.skepsun.kototoro.core.parser.legado.runtime

import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface

/**
 * AnalyzeRule 当前所需的最小运行时上下文。
 *
 * 第一阶段只收口：
 * - JS 执行
 * - 变量读写
 */
interface LegadoRuleRuntimeContext {
    fun evalJs(script: String, result: Any?, baseUrl: String): Any?

    fun executeJs(script: String, result: Any?, baseUrl: String): Any?

    fun reGetBook()

    fun refreshTocUrl()

    fun putVariable(key: String, value: String)

    fun putVariableAny(key: String, value: Any?)

    fun getVariable(key: String): String?

    fun getVariableAny(key: String): Any?

    fun putSourceVariable(key: String, value: String): String

    fun getSourceVariable(key: String): String

    fun setBook(book: BookInfo?)

    fun getBook(): BookInfo?

    fun setChapter(chapter: ChapterInfo?)

    fun getChapter(): ChapterInfo?

    fun getRuleData(): RuleDataInterface? = null

    fun getHttpExecutor(): LegadoHttpExecutor? = null

    /** Lets each host preserve its request and browser handling for rule-level AJAX. */
    fun ajax(url: String, baseUrl: String, ruleData: RuleDataInterface?): String? =
        LegadoRuleAjax.execute(this, url, baseUrl)

    fun getSourceObject(): Any? = null

    fun getSourceTag(): String? = null

    fun getParserSourceName(): String? = null

    fun <T> withJavaBridge(javaBridge: Any?, block: () -> T): T = block()
}

/** Android's sandbox supplies its runtime adapter without exposing Android types to the rule engine. */
fun interface LegadoRuleContextProvider {
    fun createRuleRuntimeContext(): LegadoRuleRuntimeContext
}
