package org.skepsun.kototoro.core.parser.legado.runtime

import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.RuleData
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface

/**
 * 不依赖 LegadoSandbox 的最小运行时状态容器。
 *
 * 第一阶段仅服务于：
 * - AnalyzeUrl 的 JS 求值
 * - AnalyzeRule 的变量读写
 * - 最小列表链路试跑
 */
class StandaloneLegadoRuntimeState(
    private val ruleData: RuleDataInterface = RuleData(),
    private val sourceKey: String? = null,
    private val variableStore: LegadoVariableStore? = null,
) {

    private val variables = LinkedHashMap<String, Any?>()
    private val cache = LinkedHashMap<String, String>()
    private var bookInfo: BookInfo? = null
    private var chapterInfo: ChapterInfo? = null

    init {
        loadPersistedVariables()
    }

    fun ruleData(): RuleDataInterface = ruleData

    fun putVariable(key: String, value: Any?) {
        variables[key] = value
        ruleData.putVariable(key, value?.toString())
        if (key.shouldPersistVariable()) {
            persistVariable(key, value?.toString())
        }
    }

    fun getVariable(key: String): String? {
        val value = variables[key]
        if (value is String && value.isNotBlank()) return value
        if (value != null) return value.toString()
        ruleData.getVariable(key)?.let { return it }
        return loadPersistedVariable(key)
    }

    fun getVariableAny(key: String): Any? {
        return variables[key]
            ?: ruleData.getVariable(key)
            ?: loadPersistedVariable(key)
    }

    fun getVariablesSnapshot(): Map<String, Any?> {
        val snapshot = LinkedHashMap<String, Any?>()
        ruleData.getVariableMap().forEach { (key, value) ->
            snapshot[key] = value
        }
        snapshot.putAll(variables)
        return snapshot
    }

    fun putSourceVariable(key: String, value: String): String {
        putVariable(key, value)
        return value
    }

    fun getSourceVariable(key: String): String {
        return getVariable(key).orEmpty()
    }

    fun setBook(book: BookInfo?) {
        bookInfo = book
        if (book == null) return
        putVariable("bookName", book.name.orEmpty())
        putVariable("bookAuthor", book.author.orEmpty())
        putVariable("bookUrl", book.bookUrl)
        putVariable("tocUrl", book.tocUrl.orEmpty())
    }

    fun getBook(): BookInfo? = bookInfo

    fun setChapter(chapter: ChapterInfo?) {
        chapterInfo = chapter
        if (chapter == null) return
        putVariable("chapterName", chapter.name)
        putVariable("chapterUrl", chapter.chapterUrl)
        putVariable("chapterIndex", chapter.index.toString())
    }

    fun getChapter(): ChapterInfo? = chapterInfo

    fun putCache(key: String, value: String?) {
        if (value == null) {
            cache.remove(key)
        } else {
            cache[key] = value
        }
    }

    fun getCache(key: String): String? = cache[key]

    fun removeCache(key: String) {
        cache.remove(key)
    }

    private fun loadPersistedVariables() {
        val key = sourceKey?.takeIf { it.isNotBlank() } ?: return
        mergeJsonVariables(variableStore?.getSourceVariable(key))
        mergeJsonVariables(variableStore?.getLoginInfo(key))
    }

    private fun persistVariable(key: String, value: String?) {
        val source = sourceKey?.takeIf { it.isNotBlank() } ?: return
        val store = variableStore ?: return

        val currentJson = runCatching {
            org.json.JSONObject(store.getSourceVariable(source) ?: "{}")
        }.getOrDefault(org.json.JSONObject())
        if (value == null) {
            currentJson.remove(key)
        } else {
            currentJson.put(key, value)
        }
        store.putSourceVariable(
            source,
            currentJson.takeIf { it.length() > 0 }?.toString(),
        )
    }

    private fun String.shouldPersistVariable(): Boolean {
        if (isBlank()) return false
        return this !in setOf(
            "baseUrl",
            "book",
            "bookName",
            "bookAuthor",
            "bookUrl",
            "tocUrl",
            "chapter",
            "chapterName",
            "chapterUrl",
            "chapterIndex",
            "source",
            "java",
            "cookie",
            "cache",
            "result",
            "src",
            "page",
            "key",
            "title",
            "index",
            "nextChapterUrl",
            "speakText",
        )
    }

    private fun loadPersistedVariable(key: String): String? {
        val source = sourceKey?.takeIf { it.isNotBlank() } ?: return null
        val store = variableStore ?: return null
        findJsonVariable(store.getSourceVariable(source), key)?.let { return it }
        findJsonVariable(store.getLoginInfo(source), key)?.let { return it }
        store.getVariable(source, key)?.let { return it }
        return null
    }

    private fun mergeJsonVariables(rawJson: String?) {
        val json = runCatching { org.json.JSONObject(rawJson ?: "{}") }.getOrNull() ?: return
        val keys = json.keys()
        while (keys.hasNext()) {
            val variableKey = keys.next()
            val variableValue = json.opt(variableKey)
            variables[variableKey] = variableValue
            ruleData.putVariable(variableKey, variableValue?.toString())
        }
    }

    private fun findJsonVariable(rawJson: String?, key: String): String? {
        val json = runCatching { org.json.JSONObject(rawJson ?: "{}") }.getOrNull() ?: return null
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key)
    }
}
