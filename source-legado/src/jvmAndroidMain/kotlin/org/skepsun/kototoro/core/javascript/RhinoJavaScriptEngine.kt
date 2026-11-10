package org.skepsun.kototoro.core.javascript

import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource

/**
 * 基于 Rhino 的 JavaScript 引擎实现。
 *
 * 对齐 legado-with-MD3：
 * - 引擎仅复用标准对象能力，不复用每次执行的 runtime 变量作用域。
 * - 每次执行都新建独立 scope，并在需要时挂接 source.jsLib 的共享 prototype。
 * - java/cookie/source/book/result 等绑定按执行上下文即时注入。
 */
class RhinoJavaScriptEngine(
    private val platform: LegadoScriptPlatform,
    private val cookieJar: LegadoScriptCookies = platform.fallbackCookies(),
) : JavaScriptEngine {

    private var standardScope: Scriptable? = null
    private val registeredGlobals = LinkedHashMap<String, Any>()

    init {
        Log.i(TAG, "Initializing RhinoJavaScriptEngine...")
        initializeEngine()
        Log.i(TAG, "RhinoJavaScriptEngine initialization completed")
    }

    private fun initializeEngine() {
        Log.i(TAG, "Starting Rhino engine initialization...")
        val ctx = platform.enterRhinoContext()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = 200
            standardScope = ctx.initStandardObjects()
            Log.i(TAG, "Rhino engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rhino engine", e)
        } finally {
            RhinoContext.exit()
        }
    }

    override fun execute(script: String, context: JavaScriptContext): Any? {
        val currentCtx = platform.enterRhinoContext()
        return try {
            currentCtx.optimizationLevel = -1
            currentCtx.languageVersion = 200

            val baseScope = standardScope ?: currentCtx.initStandardObjects().also {
                standardScope = it
            }
            val runtimeScope = createExecutionScope(currentCtx, baseScope, context)
            val defaultJavaApi = LegadoJavaAPI(platform, cookieJar).also {
                it.jsContext = context
            }
            val cookieApi = LegadoCookieAPI(cookieJar)

            bindRegisteredGlobals(currentCtx, runtimeScope)
            setContextVariables(currentCtx, runtimeScope, context)
            bindJavaBridge(currentCtx, runtimeScope, context, defaultJavaApi)
            ScriptableObject.putProperty(runtimeScope, "cookie", RhinoContext.javaToJS(cookieApi, runtimeScope))

            val contentToSet = context.result ?: context.getVariable("result")
            if (contentToSet != null) {
                defaultJavaApi.setContent(contentToSet)
            }

            val evalResult = currentCtx.evaluateString(runtimeScope, wrapScript(script), "script", 1, null)
            syncScopeBackToContext(runtimeScope, context)
            toHostResult(runtimeScope, evalResult)
        } catch (e: Exception) {
            Log.e(TAG, "Script execution failed: ${scriptPreviewForLog(script)}")
            Log.e(TAG, "Exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            RhinoContext.exit()
        }
    }

    override fun evaluate(expression: String, context: JavaScriptContext): Any? {
        return execute(expression, context)
    }

    override fun registerGlobalObject(name: String, obj: Any) {
        registeredGlobals[name] = obj
    }

    override fun dispose() {
        standardScope = null
        registeredGlobals.clear()
    }

    private fun createExecutionScope(
        currentCtx: RhinoContext,
        baseScope: Scriptable,
        context: JavaScriptContext,
    ): Scriptable {
        val runtimeScope = currentCtx.newObject(baseScope)
        runtimeScope.parentScope = baseScope
        SharedJsScope.getScope(
            jsLib = context.source?.jsLib,
            rhinoContext = currentCtx,
            platform = platform,
        )?.let { sharedScope ->
            runtimeScope.prototype = sharedScope
        }
        return runtimeScope
    }

    private fun bindRegisteredGlobals(
        currentCtx: RhinoContext,
        currentScope: Scriptable,
    ) {
        registeredGlobals.forEach { (name, obj) ->
            ScriptableObject.putProperty(currentScope, name, RhinoContext.javaToJS(obj, currentScope))
        }
    }

    private fun toHostResult(currentScope: Scriptable, evalResult: Any?): Any? {
        val jsResult = RhinoContext.jsToJava(evalResult, Any::class.java)
        if (jsResult == null || jsResult.toString() == "undefined") {
            val resultVar = ScriptableObject.getProperty(currentScope, "result")
            if (resultVar != Scriptable.NOT_FOUND) {
                val resultValue = RhinoContext.jsToJava(resultVar, Any::class.java)
                if (resultValue != null && resultValue.toString() != "undefined") {
                    return resultValue
                }
            }
        }
        return jsResult
    }

    private fun setContextVariables(
        ctx: RhinoContext,
        currentScope: Scriptable,
        context: JavaScriptContext,
    ) {
        val allVariables = context.getAllVariables()

        fun toJsValue(value: Any?): Any? {
            return when (value) {
                null -> null
                is List<*> -> {
                    val array = ctx.newArray(currentScope, value.size)
                    value.forEachIndexed { index, item ->
                        ScriptableObject.putProperty(array, index, toJsValue(item))
                    }
                    array
                }
                is Array<*> -> {
                    val array = ctx.newArray(currentScope, value.size)
                    value.forEachIndexed { index, item ->
                        ScriptableObject.putProperty(array, index, toJsValue(item))
                    }
                    array
                }
                is Map<*, *> -> {
                    val obj = ctx.newObject(currentScope)
                    value.forEach { (k, v) ->
                        val key = k?.toString() ?: return@forEach
                        ScriptableObject.putProperty(obj, key, toJsValue(v))
                    }
                    obj
                }
                is String -> ctx.newObject(currentScope, "String", arrayOf(value))
                else -> RhinoContext.javaToJS(value, currentScope)
            }
        }

        allVariables.forEach { (name, value) ->
            ScriptableObject.putProperty(currentScope, name, toJsValue(value))
        }

        context.source?.let { source ->
            val sourceWrapper = SourceWrapper(
                source = source,
                platform = platform,
                cookieJar = cookieJar,
                jsExecutor = { script, scriptContext -> execute(script, scriptContext) },
                contextFactory = { extraBindings ->
                    JavaScriptContext(
                        baseUrl = context.baseUrl,
                        book = context.book,
                        chapter = context.chapter,
                        source = context.source,
                        sourceName = context.sourceName,
                        runtimeContext = context.runtimeContext,
                        key = context.key,
                        page = context.page,
                        result = context.result,
                    ).also { nestedContext ->
                        context.getAllVariables().forEach { (name, value) ->
                            nestedContext.setVariable(name, value)
                        }
                        extraBindings.forEach { (name, value) ->
                            nestedContext.setVariable(name, value)
                        }
                    }
                },
            )
            ScriptableObject.putProperty(currentScope, "source", RhinoContext.javaToJS(sourceWrapper, currentScope))
        }

        context.book?.let { book ->
            val sourceKey = context.source?.bookSourceUrl.orEmpty()
            val bookWrapper = BookWrapper(book, platform, sourceKey)
            ScriptableObject.putProperty(currentScope, "book", RhinoContext.javaToJS(bookWrapper, currentScope))
        }
    }

    private fun bindJavaBridge(
        ctx: RhinoContext,
        currentScope: Scriptable,
        context: JavaScriptContext,
        defaultJavaApi: LegadoJavaAPI,
    ) {
        val defaultWrapper = RhinoContext.javaToJS(defaultJavaApi, currentScope) as? Scriptable
            ?: return ScriptableObject.putProperty(currentScope, "java", RhinoContext.javaToJS(defaultJavaApi, currentScope))
        val bridge = context.javaBridge
        val effectiveJava = if (bridge == null) {
            defaultWrapper
        } else {
            val bridgeWrapper = RhinoContext.javaToJS(bridge, currentScope) as? Scriptable
                ?: defaultWrapper
            CompositeJavaBinding(
                primaryWrapper = bridgeWrapper,
                fallbackWrapper = defaultWrapper,
                ownerScope = currentScope,
            )
        }
        ScriptableObject.putProperty(currentScope, "java", effectiveJava)
        if (bridge is JavaScriptBridgeBindings) {
            bridge.getBridgeBindings().forEach { (name, value) ->
                ScriptableObject.putProperty(currentScope, name, toJsValue(ctx, currentScope, value))
            }
        }
    }

    private fun toJsValue(
        ctx: RhinoContext,
        currentScope: Scriptable,
        value: Any?,
    ): Any? {
        return when (value) {
            null -> null
            is List<*> -> {
                val array = ctx.newArray(currentScope, value.size)
                value.forEachIndexed { index, item ->
                    ScriptableObject.putProperty(array, index, toJsValue(ctx, currentScope, item))
                }
                array
            }
            is Array<*> -> {
                val array = ctx.newArray(currentScope, value.size)
                value.forEachIndexed { index, item ->
                    ScriptableObject.putProperty(array, index, toJsValue(ctx, currentScope, item))
                }
                array
            }
            is Map<*, *> -> {
                val obj = ctx.newObject(currentScope)
                value.forEach { (k, v) ->
                    val key = k?.toString() ?: return@forEach
                    ScriptableObject.putProperty(obj, key, toJsValue(ctx, currentScope, v))
                }
                obj
            }
            is String -> ctx.newObject(currentScope, "String", arrayOf(value))
            else -> RhinoContext.javaToJS(value, currentScope)
        }
    }

    private fun syncScopeBackToContext(
        currentScope: Scriptable,
        context: JavaScriptContext,
    ) {
        val protectedKeys = setOf(
            "java",
            "cookie",
            "cache",
            "source",
            "book",
            "chapter",
            "Packages",
            "getClass",
            "JavaAdapter",
            "JavaImporter",
            "Continuation",
            "importClass",
            "importPackage",
        )
        currentScope.ids.forEach { id ->
            val key = when (id) {
                is String -> id
                is Number -> return@forEach
                else -> id?.toString() ?: return@forEach
            }
            if (key in protectedKeys) return@forEach
            val rawValue = ScriptableObject.getProperty(currentScope, key)
            if (rawValue == Scriptable.NOT_FOUND) return@forEach
            val value = fromJsValue(rawValue)
            when (key) {
                "result" -> context.result = value
                "src" -> context.src = value
                else -> context.setVariable(key, value)
            }
        }
    }

    private fun fromJsValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is NativeJavaObject -> value.unwrap()
            else -> runCatching { RhinoContext.jsToJava(value, Any::class.java) }.getOrDefault(value)
        }
    }

    private fun scriptPreviewForLog(script: String, limit: Int = 220): String {
        val normalized = script.replace("\r", "").replace("\n", "\\n").trim()
        if (normalized.length <= limit) return normalized
        return normalized.take(limit) + "…(len=${normalized.length})"
    }

    class SourceWrapper(
        private val source: LegadoBookSource,
        private val platform: LegadoScriptPlatform,
        private val cookieJar: LegadoScriptCookies,
        private val jsExecutor: (String, JavaScriptContext) -> Any?,
        private val contextFactory: (Map<String, Any?>) -> JavaScriptContext,
    ) {
        private val prefs by lazy { platform.store(PREFS_NAME) }
        private val cookieApi by lazy {
            LegadoCookieAPI(cookieJar)
        }

        val bookSourceComment: String? get() = source.bookSourceComment
        val bookSourceName: String get() = source.bookSourceName
        val bookSourceUrl: String get() = source.bookSourceUrl
        val bookSourceType: Int get() = source.bookSourceType
        val bookSourceGroup: String? get() = source.bookSourceGroup
        val header: String? get() = source.header
        val loginUrl: String? get() = source.loginUrl
        val loginUi: String? get() = source.loginUi
        val loginCheckJs: String? get() = source.loginCheckJs
        val enabledCookieJar: Boolean? get() = source.enabledCookieJar
        val jsLib: String? get() = source.jsLib
        val concurrentRate: String? get() = source.concurrentRate
        val enabled: Boolean get() = source.enabled
        val weight: Int get() = source.weight
        val eventListener: Boolean get() = source.eventListener
        val customButton: Boolean get() = source.customButton

        fun getTag(): String {
            return source.bookSourceName
        }

        fun getKey(): String {
            return source.bookSourceUrl
        }

        fun getSource(): SourceWrapper {
            return this
        }

        fun getSourceType(): Int {
            return SOURCE_TYPE_BOOK
        }

        fun getVariable(): String {
            return prefs.getString(sourceVariableKey()) ?: ""
        }

        fun setVariable(variable: String?) {
            prefs.putString(sourceVariableKey(), variable)
        }

        fun putVariable(variable: String?) {
            setVariable(variable)
        }

        fun put(key: String, value: String): String {
            return contextFactory(emptyMap()).runtimeContext?.putSourceVariable(key, value)
                ?: value.also {
                    prefs.putString(kvKey(key), value)
                }
        }

        fun get(key: String): String {
            return contextFactory(emptyMap()).runtimeContext?.getSourceVariable(key)
                ?: (prefs.getString(kvKey(key)) ?: "")
        }

        fun getLoginJs(): String? {
            val loginJs = source.loginUrl
            return when {
                loginJs == null -> null
                loginJs.startsWith("@js:", true) -> loginJs.substring(4)
                loginJs.startsWith("<js>", true) -> loginJs.substring(4, loginJs.lastIndexOf("<"))
                else -> loginJs
            }
        }

        fun login() {
            val loginJs = getLoginJs()
            if (!loginJs.isNullOrBlank()) {
                val script = """$loginJs
                    if(typeof login=='function'){
                        login.apply(this);
                    } else {
                        throw('Function login not implements!!!')
                    }
                """.trimIndent()
                evalJS(script)
            }
        }

        fun getLoginHeaderMap(): Map<String, String>? {
            val cache = getLoginHeader() ?: return null
            if (cache.isBlank()) return null
            return runCatching {
                val obj = org.json.JSONObject(cache)
                val result = LinkedHashMap<String, String>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    result[k] = obj.optString(k, "")
                }
                result
            }.getOrNull()
        }

        fun getHeaderMap(hasLoginHeader: Boolean = false): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            source.header?.takeIf { it.isNotBlank() }?.let { rawHeader ->
                val json = try {
                    when {
                        rawHeader.startsWith("@js:", true) -> evalJS(rawHeader.substring(4)).toString()
                        rawHeader.startsWith("<js>", true) -> {
                            val end = rawHeader.lastIndexOf("<")
                            evalJS(rawHeader.substring(4, end)).toString()
                        }
                        else -> rawHeader
                    }
                } catch (_: Exception) {
                    rawHeader
                }
                runCatching {
                    val obj = org.json.JSONObject(json)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        result[k] = obj.optString(k, "")
                    }
                }
            }
            if (result.keys.none { it.equals(USER_AGENT_HEADER, ignoreCase = true) }) {
                result[USER_AGENT_HEADER] = platform.defaultUserAgent
            }
            if (hasLoginHeader) {
                getLoginHeaderMap()?.let(result::putAll)
            }
            return result
        }

        fun putLoginInfo(info: String): Boolean {
            return runCatching {
                prefs.putString(loginInfoKey(), info)
            }.isSuccess
        }

        fun getLoginInfo(): String? {
            return prefs.getString(loginInfoKey())
        }

        fun getLoginInfoMap(): Map<String, String> {
            val storedJson = getLoginInfo()
            if (!storedJson.isNullOrBlank()) {
                return parseJsonObject(storedJson).orEmpty()
            }
            val loginUiRule = source.loginUi?.takeIf { it.isNotBlank() } ?: return emptyMap()
            val loginUiJson = resolveLoginUiJson(loginUiRule) ?: return emptyMap()
            val defaults = parseLoginUiDefaults(loginUiJson)
            if (defaults.isNotEmpty()) {
                putLoginInfo(org.json.JSONObject(defaults as Map<*, *>).toString())
            }
            return defaults
        }

        fun removeLoginInfo() {
            prefs.putString(loginInfoKey(), null)
        }

        fun putLoginHeader(header: String?): Boolean {
            return runCatching {
                header
                    ?.let(::parseHeaderMap)
                    ?.let { headerMap ->
                        headerMap["Cookie"]?.takeIf { it.isNotBlank() }
                            ?: headerMap["cookie"]?.takeIf { it.isNotBlank() }
                    }
                    ?.let { cookie ->
                        cookieApi.replaceCookie(getKey(), cookie)
                    }
                prefs.putString(loginHeaderKey(), header)
            }.isSuccess
        }

        fun getLoginHeader(): String? {
            return prefs.getString(loginHeaderKey())
        }

        fun removeLoginHeader() {
            prefs.putString(loginHeaderKey(), null)
            cookieApi.removeCookie(getKey())
        }

        fun refreshJSLib() {
            SharedJsScope.remove(source.jsLib)
        }

        fun refreshExplore() {
            // Kototoro 目前未做发现分类额外缓存；保留接口以对齐 MD3。
        }

        fun putConcurrent(value: String) {
            prefs.putString(concurrentRateKey(), value)
        }

        fun getConcurrent(): String {
            return prefs.getString(concurrentRateKey()) ?: source.concurrentRate ?: ""
        }


        fun evalJS(jsStr: String, bindings: Map<String, Any?> = emptyMap()): Any? {
            val jsContext = contextFactory(bindings)
            return jsExecutor(jsStr, jsContext)
        }

        private fun parseHeaderMap(raw: String): Map<String, String>? {
            if (raw.isBlank()) return null
            return parseJsonObject(raw)
        }

        private fun parseJsonObject(raw: String): Map<String, String>? {
            return runCatching {
                val obj = org.json.JSONObject(raw)
                val result = LinkedHashMap<String, String>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result[key] = obj.optString(key, "")
                }
                result
            }.getOrNull()
        }

        private fun resolveLoginUiJson(rule: String): String? {
            return when {
                rule.startsWith("@js:", true) -> {
                    val loginJs = getLoginJs().orEmpty()
                    evalJS("$loginJs\n${rule.substring(4)}", loginUiBindings())?.toString()
                }
                rule.startsWith("<js>", true) -> {
                    val end = rule.lastIndexOf("<")
                    val loginJs = getLoginJs().orEmpty()
                    evalJS("$loginJs\n${rule.substring(4, end)}", loginUiBindings())?.toString()
                }
                else -> rule
            }
        }

        private fun loginUiBindings(): Map<String, Any?> {
            return mapOf(
                "result" to mutableMapOf<String, String>(),
                "book" to null,
                "chapter" to null,
            )
        }

        private fun parseLoginUiDefaults(raw: String): Map<String, String> {
            return runCatching {
                val array = org.json.JSONArray(raw)
                val result = LinkedHashMap<String, String>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (item.optString("type") == "button") continue
                    val name = item.optString("name")
                    if (name.isBlank()) continue
                    result[name] = item.optString("default", "")
                }
                result
            }.getOrElse { emptyMap() }
        }

        private fun sourceVariableKey(): String = "sourceVariable_${getKey()}"
        private fun loginInfoKey(): String = "userInfo_${getKey()}"
        private fun loginHeaderKey(): String = "loginHeader_${getKey()}"
        private fun concurrentRateKey(): String = "concurrentRate_${getKey()}"
        private fun kvKey(key: String): String = "v_${getKey()}_$key"

        private companion object {
            private const val PREFS_NAME = "legado_source_store"
            private const val USER_AGENT_HEADER = "User-Agent"
            private const val SOURCE_TYPE_BOOK = 0
        }
    }

    class BookWrapper(
        private val book: BookInfo,
        private val platform: LegadoScriptPlatform,
        private val sourceKey: String,
    ) {
        private val prefs by lazy { platform.store(PREFS_NAME) }

        val bookUrl: String get() = book.bookUrl
        val name: String? get() = book.name
        val author: String? get() = book.author
        val coverUrl: String? get() = book.coverUrl
        val intro: String? get() = book.intro
        val kind: String? get() = book.kind
        val lastChapter: String? get() = book.lastChapter
        val tocUrl: String? get() = book.tocUrl
        val wordCount: String? get() = book.wordCount
        val type: Int? get() = book.type

        fun putVariable(key: String, value: String?): Boolean {
            book.putVariable(key, value)
            prefs.putString(bookVariableKey(key), value)
            Log.d(TAG, "book.putVariable($key, $value) -> persisted to prefs")
            return true
        }

        fun getVariable(key: String): String {
            val memValue = book.getVariable(key)
            if (memValue.isNotEmpty()) {
                return memValue
            }
            val persistedValue = prefs.getString(bookVariableKey(key)) ?: ""
            if (persistedValue.isNotEmpty()) {
                book.putVariable(key, persistedValue)
                Log.d(TAG, "book.getVariable($key) -> loaded from prefs: $persistedValue")
                return persistedValue
            }
            val defaultValue = prefs.getString(bookDefaultKey(key)) ?: ""
            if (defaultValue.isNotEmpty()) {
                book.putVariable(key, defaultValue)
                Log.d(TAG, "book.getVariable($key) -> loaded default from prefs: $defaultValue")
            }
            return defaultValue
        }

        fun setUseReplaceRule(useReplaceRule: Boolean) {
            book.setUseReplaceRule(useReplaceRule)
        }

        fun getUseReplaceRule(): Boolean {
            return book.getUseReplaceRule()
        }

        fun setProperty(name: String, value: Any?) {
            book.setProperty(name, value)
        }

        fun getProperty(propertyName: String): Any? {
            return book.getProperty(propertyName)
        }

        private fun bookVariableKey(key: String): String {
            val urlHash = book.bookUrl.hashCode().toString(16)
            return "bookVar_${urlHash}_$key"
        }

        private fun bookDefaultKey(key: String): String {
            val sourceHash = sourceKey.hashCode().toString(16)
            return "bookVar_default_${sourceHash}_$key"
        }

        private companion object {
            private const val TAG = "BookWrapper"
            private const val PREFS_NAME = "legado_book_store"
        }
    }

    companion object {
        private const val TAG = "RhinoJavaScriptEngine"

        fun wrapScript(raw: String): String {
            var script = raw.trim()
            if (script.startsWith("@js:", ignoreCase = true)) {
                script = script.removePrefix("@js:").trim()
            }
            if (script.startsWith("<js>", ignoreCase = true)) {
                script = script.removePrefix("<js>").trim()
            }
            if (script.endsWith("</js>", ignoreCase = true)) {
                script = script.removeSuffix("</js>").trim()
            }
            script = script.replace(Regex("\\bcatch\\s*\\{"), "catch(e){")
            return script
        }
    }
}
