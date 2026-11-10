package org.skepsun.kototoro.core.javascript

/**
 * JavaScript 引擎接口
 *
 * 提供执行 JavaScript 代码的能力，用于支持 Legado 书源中的 JavaScript 规则
 */
interface JavaScriptEngine {

    /**
     * 执行 JavaScript 代码
     *
     * @param script JavaScript 代码
     * @param context 执行上下文（变量）
     * @return 执行结果
     */
    fun execute(script: String, context: JavaScriptContext): Any?

    /**
     * 执行 JavaScript 表达式
     *
     * @param expression JavaScript 表达式
     * @param context 执行上下文
     * @return 表达式结果
     */
    fun evaluate(expression: String, context: JavaScriptContext): Any?

    /**
     * 注册全局对象
     *
     * @param name 对象名称
     * @param obj 对象实例
     */
    fun registerGlobalObject(name: String, obj: Any)

    /**
     * 清理引擎资源
     */
    fun dispose()
}
