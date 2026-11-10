package org.skepsun.kototoro.core.parser.legado.runtime

/**
 * HTTP 执行端口。
 */
interface LegadoHttpExecutor {
    suspend fun execute(plan: LegadoRequestPlan): LegadoHttpResponse
}

/**
 * Cookie 存取端口。
 */
interface LegadoCookieStore {
    fun getCookie(urlOrKey: String): String?

    fun setCookie(urlOrKey: String, cookie: String?)

    fun replaceCookie(urlOrKey: String, cookie: String)

    fun removeCookie(urlOrKey: String)
}

/**
 * 变量与登录信息存取端口。
 */
interface LegadoVariableStore {
    /** Reads an older per-key variable when it is absent from the source's JSON blob. */
    fun getVariable(sourceKey: String, key: String): String? = null

    fun getSourceVariable(sourceKey: String): String?

    fun putSourceVariable(sourceKey: String, value: String?)

    fun getLoginInfo(sourceKey: String): String?

    fun putLoginInfo(sourceKey: String, value: String?)

    fun getLoginHeader(sourceKey: String): String?

    fun putLoginHeader(sourceKey: String, value: String?)
}

/**
 * WebView 抓取端口。
 */
interface LegadoWebViewFetcher {
    suspend fun fetch(request: LegadoWebViewRequest): LegadoWebViewResult
}

/**
 * 运行时日志端口。
 */
interface LegadoRuntimeLogger {
    fun debug(tag: String, message: String)

    fun warn(tag: String, message: String, throwable: Throwable? = null)
}
