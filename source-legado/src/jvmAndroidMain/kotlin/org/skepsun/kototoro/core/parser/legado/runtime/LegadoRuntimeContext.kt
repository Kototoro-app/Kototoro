package org.skepsun.kototoro.core.parser.legado.runtime

import org.skepsun.kototoro.core.parser.legado.model.LegadoRuleContext
import org.skepsun.kototoro.core.parser.legado.model.LegadoSourceDescriptor

/**
 * Legado 规则运行时上下文。
 *
 * 该上下文把宿主能力显式收口，避免规则核心直接依赖仓库、SharedPreferences 或 App 单例。
 */
data class LegadoRuntimeContext(
    val source: LegadoSourceDescriptor? = null,
    val ruleContext: LegadoRuleContext = LegadoRuleContext(source = source),
    val httpExecutor: LegadoHttpExecutor,
    val cookieStore: LegadoCookieStore,
    val variableStore: LegadoVariableStore,
    val webViewFetcher: LegadoWebViewFetcher? = null,
    val logger: LegadoRuntimeLogger = NoOpLegadoRuntimeLogger,
)

object NoOpLegadoRuntimeLogger : LegadoRuntimeLogger {
    override fun debug(tag: String, message: String) = Unit

    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
}
