package org.skepsun.kototoro.core.javascript

/**
 * 书籍信息
 * 与 Legado 的 Book 对象保持一致
 */
data class BookInfo(
    val bookUrl: String,
    var name: String? = null,
    var author: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var tocUrl: String? = null,
    var wordCount: String? = null,
    var type: Int? = null, // 自定义属性，可被 JavaScript 设置
    val customProperties: MutableMap<String, Any?> = mutableMapOf(),
    private val variableMap: MutableMap<String, String> = mutableMapOf()
) {
    // Legado 兼容：是否使用替换规则
    private var useReplaceRule: Boolean = true

    /**
     * 设置自定义属性
     */
    fun setProperty(name: String, value: Any?) {
        when (name) {
            "name" -> this.name = value?.toString()
            "author" -> this.author = value?.toString()
            "coverUrl" -> this.coverUrl = value?.toString()
            "intro" -> this.intro = value?.toString()
            "kind" -> this.kind = value?.toString()
            "lastChapter" -> this.lastChapter = value?.toString()
            "tocUrl" -> this.tocUrl = value?.toString()
            "wordCount" -> this.wordCount = value?.toString()
            "type" -> this.type = value?.toString()?.toIntOrNull()
            else -> customProperties[name] = value
        }
    }

    /**
     * 获取属性
     */
    fun getProperty(propertyName: String): Any? {
        return when (propertyName) {
            "bookUrl" -> bookUrl
            "name" -> this.name
            "author" -> this.author
            "coverUrl" -> this.coverUrl
            "intro" -> this.intro
            "kind" -> this.kind
            "lastChapter" -> this.lastChapter
            "tocUrl" -> this.tocUrl
            "wordCount" -> this.wordCount
            "type" -> this.type
            else -> customProperties[propertyName]
        }
    }

    // ======== Legado 兼容：变量管理 ========

    /**
     * 存储变量（Legado 兼容）
     * 用于聚合源等场景，在 JavaScript 中调用 book.putVariable(key, value)
     */
    fun putVariable(key: String, value: String?): Boolean {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
        return true
    }

    /**
     * 获取变量（Legado 兼容）
     * 用于聚合源等场景，在 JavaScript 中调用 book.getVariable(key)
     */
    fun getVariable(key: String): String {
        return variableMap[key] ?: ""
    }

    /**
     * 设置是否使用替换规则（Legado 兼容）
     * 部分聚合源会调用 book.setUseReplaceRule(false) 来禁用净化替换
     */
    fun setUseReplaceRule(useReplaceRule: Boolean) {
        this.useReplaceRule = useReplaceRule
    }

    /**
     * 获取是否使用替换规则
     */
    fun getUseReplaceRule(): Boolean {
        return useReplaceRule
    }

    /**
     * 获取所有变量（用于序列化等）
     */
    fun getVariableMap(): Map<String, String> {
        return variableMap.toMap()
    }
}

/**
 * 章节信息
 */
data class ChapterInfo(
    val chapterUrl: String,
    var name: String,
    val index: Int,
    private val variableMap: MutableMap<String, String> = mutableMapOf(),
) {
    fun putVariable(key: String, value: String?): Boolean {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
        return true
    }

    fun getVariable(key: String): String {
        return variableMap[key] ?: ""
    }

    fun getVariableMap(): Map<String, String> {
        return variableMap.toMap()
    }
}
