package org.skepsun.kototoro.core.parser.legado.runtime

/**
 * Legado URL/规则求值期使用的最小 JS 执行抽象。
 *
 * 只暴露当前 AnalyzeUrl 拆分所需能力，避免模板组件直接依赖具体 sandbox。
 */
fun interface LegadoJsEvaluator {
    fun evaluate(script: String, result: Any?): Any?
}
