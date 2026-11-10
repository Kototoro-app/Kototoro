package org.skepsun.kototoro.core.javascript

import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.LegadoReflectiveAccess
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext

/**
 * JavaScript 执行上下文
 *
 * 管理 JavaScript 执行时的变量和状态
 * 与 Legado 的 AnalyzeRule.kt 保持一致
 */
data class JavaScriptContext(
    private val variables: MutableMap<String, Any?> = mutableMapOf(),
    val baseUrl: String? = null,
    val book: BookInfo? = null,
    val chapter: ChapterInfo? = null,
    val source: LegadoBookSource? = null,
    val sourceName: String? = null,
    val runtimeContext: LegadoRuleRuntimeContext? = null,
    val key: String? = null,
    val page: Int? = null,
    var result: Any? = null,  // 当前规则的输入/输出数据
    var src: Any? = null,
) {
    var javaBridge: Any? = null

    /**
     * 设置变量
     */
    fun setVariable(name: String, value: Any?) {
        when (name) {
            "result" -> result = value
            "src" -> src = value
            else -> variables[name] = value
        }
    }

    /**
     * 获取变量
     * 支持嵌套属性访问，如 "book.name"
     */
    fun getVariable(name: String): Any? {
        // 首先检查直接变量
        if (variables.containsKey(name)) {
            return variables[name]
        }

        // 处理嵌套属性访问
        if (name.contains('.')) {
            val parts = name.split('.', limit = 2)
            val objectName = parts[0]
            val propertyName = parts[1]

            val obj = when (objectName) {
                "book" -> book
                "chapter" -> chapter
                "source" -> source
                else -> variables[objectName]
            }

            return getNestedProperty(obj, propertyName)
        }

        // 检查上下文中的标准变量
        return when (name) {
            "baseUrl" -> baseUrl
            "book" -> book
            "chapter" -> chapter
            "source" -> source
            "sourceName" -> sourceName
            "key" -> key
            "page" -> page
            "result" -> result
            "src" -> src ?: result
            else -> null
        }
    }

    /**
     * 获取嵌套属性
     * 支持多级访问，如 "book.name" 或 "book.customProperties.type"
     */
    private fun getNestedProperty(obj: Any?, propertyName: String): Any? {
        if (obj == null) return null

        val parts = propertyName.split('.', limit = 2)
        val currentProperty = parts[0]

        val value = when (obj) {
            is BookInfo -> when (currentProperty) {
                "bookUrl" -> obj.bookUrl
                "name" -> obj.name
                "author" -> obj.author
                "coverUrl" -> obj.coverUrl
                "intro" -> obj.intro
                "kind" -> obj.kind
                "lastChapter" -> obj.lastChapter
                "tocUrl" -> obj.tocUrl
                "wordCount" -> obj.wordCount
                "type" -> obj.type
                else -> obj.customProperties[currentProperty]
            }
            is ChapterInfo -> when (currentProperty) {
                "chapterUrl" -> obj.chapterUrl
                "name" -> obj.name
                "index" -> obj.index
                else -> null
            }
            is LegadoBookSource -> when (currentProperty) {
                "bookSourceName" -> obj.bookSourceName
                "bookSourceUrl" -> obj.bookSourceUrl
                "bookSourceType" -> obj.bookSourceType
                else -> null
            }
            is Map<*, *> -> obj[currentProperty]
            else -> LegadoReflectiveAccess.readProperty(obj, currentProperty)
        }

        // 如果还有更多层级，递归获取
        return if (parts.size > 1) {
            getNestedProperty(value, parts[1])
        } else {
            value
        }
    }

    /**
     * 获取所有变量（用于 JavaScript 引擎）
     */
    fun getAllVariables(): Map<String, Any?> {
        val allVars = mutableMapOf<String, Any?>()
        allVars.putAll(variables)

        // 添加标准变量
        // 允许通过 setVariable/putVariable("baseUrl", ...) 动态覆盖上下文 baseUrl
        // 显式变量优先，避免被构造时的 baseUrl 覆盖（与 legado 行为对齐）
        baseUrl?.let { if (!allVars.containsKey("baseUrl")) allVars["baseUrl"] = it }
        book?.let { allVars["book"] = it }
        chapter?.let { allVars["chapter"] = it }
        source?.let { allVars["source"] = it }
        sourceName?.let { allVars["sourceName"] = it }
        key?.let { allVars["key"] = it }
        page?.let { allVars["page"] = it }
        result?.let { allVars["result"] = it }
        (src ?: result)?.let { allVars["src"] = it }

        // java 绑定在 Sandbox.setResult 时注入，以便可替换为具备方法的绑定对象
        javaBridge?.let { allVars["java"] = it }
        variables["java"]?.let { allVars["java"] = it }
        // cookie 和 cache 将在 Sandbox 中实际注入

        return allVars
    }

    fun getMutableVariables(): Map<String, Any?> {
        return variables.toMap()
    }

    companion object {
        /**
         * 创建搜索上下文
         */
        fun forSearch(key: String, page: Int, source: LegadoBookSource): JavaScriptContext {
            return JavaScriptContext(
                key = key,
                page = page,
                source = source
            )
        }

        /**
         * 创建书籍信息上下文
         */
        fun forBookInfo(book: BookInfo, source: LegadoBookSource, baseUrl: String): JavaScriptContext {
            return JavaScriptContext(
                book = book,
                source = source,
                baseUrl = baseUrl
            )
        }

        /**
         * 创建章节列表上下文
         */
        fun forChapterList(book: BookInfo, source: LegadoBookSource, baseUrl: String): JavaScriptContext {
            return JavaScriptContext(
                book = book,
                source = source,
                baseUrl = baseUrl
            )
        }

        /**
         * 创建章节内容上下文
         */
        fun forContent(
            book: BookInfo,
            chapter: ChapterInfo,
            source: LegadoBookSource,
            baseUrl: String
        ): JavaScriptContext {
            return JavaScriptContext(
                book = book,
                chapter = chapter,
                source = source,
                baseUrl = baseUrl
            )
        }
    }
}
