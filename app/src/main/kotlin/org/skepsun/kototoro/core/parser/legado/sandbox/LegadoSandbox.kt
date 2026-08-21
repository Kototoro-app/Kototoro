package org.skepsun.kototoro.core.parser.legado.sandbox

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoRuntimeBridge
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoVariableStore
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.RuleData
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface
import org.skepsun.kototoro.core.javascript.BookInfo as JsBookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo as JsChapterInfo

/**
 * Sandboxed JavaScript execution environment for Legado sources.
 * Provides isolated variable scope and secure API bindings.
 */
class LegadoSandbox(
    private val jsEngine: JavaScriptEngine,
    private val httpClient: LegadoHttpClient,
    private val source: LegadoBookSource,
    private val parserSourceName: String? = null,
    private val cookieManager: android.webkit.CookieManager? = null,
    private val prefs: SharedPreferences? = null,
    private val runtimeBridge: KototoroLegadoRuntimeBridge? = null,
    private val variableStore: KototoroLegadoVariableStore? =
        (runtimeBridge?.variableStore as? KototoroLegadoVariableStore) ?: prefs?.let(::KototoroLegadoVariableStore),
) {

    private val cache = mutableMapOf<String, String>()
    private var reGetBookAction: (() -> Unit)? = null
    private var refreshTocUrlAction: (() -> Unit)? = null
    private var javaBridgeOverride: Any? = null

    inner class CacheBinding {
        fun put(key: String, value: String?) {
            Log.d(TAG, "cache.put(key=$key, value=${value?.take(50)})")
            if (value != null) cache[key] = value
            else cache.remove(key)
        }

        // Overload with TTL (time-to-live in seconds) - for Legado compatibility
        // Currently ignores TTL, stores indefinitely
        fun put(key: String, value: String?, ttl: Int) {
            Log.d(TAG, "cache.put(key=$key, value=${value?.take(50)}, ttl=$ttl)")
            if (value != null) cache[key] = value
            else cache.remove(key)
        }

        // Overload with TTL as Long
        fun put(key: String, value: String?, ttl: Long) {
            put(key, value, ttl.toInt())
        }

        fun get(key: String): String? {
            val result = cache[key]
            Log.d(TAG, "cache.get(key=$key) = ${result?.take(50) ?: "null"}")
            return result
        }

        fun delete(key: String) {
            Log.d(TAG, "cache.delete(key=$key)")
            cache.remove(key)
        }
    }

    companion object {
        private const val TAG = "LegadoSandbox"
    }

    private val sourceKey = source.bookSourceUrl.trim()

    private val ruleData: RuleData = RuleData()

    private var context: JavaScriptContext = JavaScriptContext(
        baseUrl = source.bookSourceUrl,
        source = source,
        sourceName = parserSourceName,
        runtimeContext = LegadoSandboxRuleRuntimeContext(this),
    )

    init {
        loadPersistedVariables()
    }

    private fun loadPersistedVariables() {
        if (sourceKey.isBlank()) return

        val variableJson = variableStore?.getSourceVariable(sourceKey) ?: prefs?.getString("sourceVariable_$sourceKey", null)
        if (!variableJson.isNullOrBlank()) {
            try {
                val obj = org.json.JSONObject(variableJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    putVariable(k, v)
                }
            } catch (_: Exception) {}
        }

        val loginInfoJson = variableStore?.getLoginInfo(sourceKey) ?: prefs?.getString("userInfo_$sourceKey", null)
        if (!loginInfoJson.isNullOrBlank()) {
            try {
                val obj = org.json.JSONObject(loginInfoJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    putVariable(k, v)
                }
            } catch (_: Exception) {}
        }
    }


    fun getRuleData(): RuleDataInterface = ruleData

    fun getSource(): LegadoBookSource = source

    fun getSourceTag(): String = source.bookSourceName

    fun getParserSourceName(): String? = parserSourceName

    fun getHttpExecutor() = runtimeBridge?.httpExecutor

    fun putVariable(key: String, value: String?) {
        ruleData.putVariable(key, value)
        context.setVariable(key, value ?: "")
        if (key.shouldPersistVariable()) {
            persistVariable(key, value)
        }
    }

    fun getVariable(key: String): String? {
        // check in-memory context first (JS scripts modify these directly)
        context.getVariable(key)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        ruleData.getVariable(key)?.let { return it }
        return loadPersistedVariable(key)
    }

    internal fun getVariableAny(key: String): Any? {
        context.getVariable(key)?.let { return it }
        return ruleData.getVariable(key)
    }

    fun putSourceVariable(key: String, value: String): String {
        putVariable(key, value)
        return value
    }

    fun getSourceVariable(key: String): String {
        return getVariable(key).orEmpty()
    }

    private fun persistVariable(key: String, value: String?) {
        if (sourceKey.isBlank()) return
        val store = variableStore
        if (store != null) {
            store.putVariable(sourceKey, key, value)
            return
        }
        val p = prefs ?: return
        val kvKey = "v_${sourceKey}_$key"
        if (value != null) {
            p.edit().putString(kvKey, value).apply()
        } else {
            p.edit().remove(kvKey).apply()
        }
    }

    private fun loadPersistedVariable(key: String): String? {
        if (sourceKey.isBlank()) return null
        variableStore?.getVariable(sourceKey, key)?.let { return it }
        val p = prefs ?: return null
        return p.getString("v_${sourceKey}_$key", null)
    }

    fun setResult(result: Any?) {
        Log.d(TAG, "setResult: type=${result?.javaClass?.simpleName}, isList=${result is List<*>}, size=${(result as? List<*>)?.size}")
        context.result = result
        context.setVariable("result", result)
    }

    fun setBook(book: BookContext) {
        val jsBook = JsBookInfo(
            bookUrl = book.url,
            name = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            kind = book.kind,
            lastChapter = book.lastChapter,
            tocUrl = book.tocUrl,
            wordCount = book.wordCount,
        )
        context = context.copy(book = jsBook)
        putVariable("bookName", book.name)
        putVariable("bookAuthor", book.author)
        putVariable("bookUrl", book.url)
        putVariable("tocUrl", book.tocUrl)
    }

    fun setChapter(chapter: ChapterContext) {
        val jsChapter = JsChapterInfo(
            chapterUrl = chapter.url,
            name = chapter.title,
            index = chapter.index
        )
        context = context.copy(chapter = jsChapter)
        putVariable("chapterName", chapter.title)
        putVariable("chapterUrl", chapter.url)
        putVariable("chapterIndex", chapter.index.toString())
    }

    fun eval(script: String): Any? {
        if (script.isBlank()) return null

        return try {
            ruleData.getVariableMap().forEach { (k, v) ->
                context.setVariable(k, v)
            }
            context.setVariable("cache", CacheBinding())
            context.javaBridge = javaBridgeOverride
            Log.d(TAG, "eval: context.result=${context.result?.javaClass?.simpleName}, preview=${context.result?.toString()?.take(50)}")
            jsEngine.evaluate(script, context).also {
                syncContextBack()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JavaScript evaluation failed: ${e.message}", e)
            null
        }
    }

    fun execute(script: String): Any? {
        if (script.isBlank()) return null

        return try {
            ruleData.getVariableMap().forEach { (k, v) ->
                context.setVariable(k, v)
            }
            context.setVariable("cache", CacheBinding())
            context.javaBridge = javaBridgeOverride
            jsEngine.execute(script, context).also {
                syncContextBack()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JavaScript execution failed: ${e.message}", e)
            null
        }
    }

    private fun syncContextBack() {
        context.getMutableVariables().forEach { (key, value) ->
            val persistedValue = value.toPersistableRuleValue()
            ruleData.putVariable(key, persistedValue)
            if (key.shouldPersistVariable()) {
                persistVariable(key, persistedValue)
            }
        }
        ruleData.putVariable("result", context.result.toPersistableRuleValue())
        ruleData.putVariable("src", context.src.toPersistableRuleValue())
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

    private fun Any?.toPersistableRuleValue(): String? {
        return when (this) {
            null -> null
            is String -> this
            is Number, is Boolean, is Char -> toString()
            else -> null
        }
    }

    fun reset() {
        ruleData.clearVariables()
        context = JavaScriptContext(
            baseUrl = source.bookSourceUrl,
            source = source,
            sourceName = parserSourceName,
            runtimeContext = LegadoSandboxRuleRuntimeContext(this),
        )
        javaBridgeOverride = null
    }

    fun <T> withJavaBridge(javaBridge: Any?, block: () -> T): T {
        val previousBridge = javaBridgeOverride
        javaBridgeOverride = javaBridge
        return try {
            block()
        } finally {
            javaBridgeOverride = previousBridge
        }
    }

    fun withPreUpdateActions(
        reGetBookAction: (() -> Unit)? = null,
        refreshTocUrlAction: (() -> Unit)? = null,
        block: () -> Unit,
    ) {
        val previousReGetBookAction = this.reGetBookAction
        val previousRefreshTocUrlAction = this.refreshTocUrlAction
        this.reGetBookAction = reGetBookAction
        this.refreshTocUrlAction = refreshTocUrlAction
        try {
            block()
        } finally {
            this.reGetBookAction = previousReGetBookAction
            this.refreshTocUrlAction = previousRefreshTocUrlAction
        }
    }

    fun reGetBook() {
        reGetBookAction?.invoke()
    }

    fun refreshTocUrl() {
        refreshTocUrlAction?.invoke()
    }

    data class BookContext(
        val name: String = "",
        val author: String = "",
        val url: String = "",
        val coverUrl: String = "",
        val intro: String = "",
        val kind: String = "",
        val lastChapter: String = "",
        val tocUrl: String = "",
        val wordCount: String = "",
    )

    data class ChapterContext(
        val title: String = "",
        val url: String = "",
        val index: Int = 0,
        val isVip: Boolean = false,
        val isPay: Boolean = false
    )
}
